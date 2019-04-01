0x01

package com.quaap.bookymcbookface.book;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import com.quaap.bookymcbookface.FsTools;


/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public abstract class Book {
    private static final String FONTSIZE = "fontsize";
    private static final String SECTION_ID_OFFSET = "sectionIDOffset";
    private static final String SECTION_ID = "sectionID";
    private static final String BG_COLOR = "BG_COLOR";
    private String title;
    private File file;

    private final File dataDir;
    private SharedPreferences data;
    private final Context context;

    private List<String> sectionIDs;
    private int currentSectionIDPos = 0;

    private String subbook;
    private File thisBookDir;

    Book(Context context) {
        this.dataDir = context.getFilesDir();
        this.context = context;
        sectionIDs = new ArrayList<>();
    }

    protected abstract void load() throws IOException;

    public abstract Map<String,String> getToc();

    protected abstract BookMetadata getMetaData() throws IOException;

    protected abstract List<String> getSectionIds();

    protected abstract Uri getUriForSectionID(String id);

    //protected abstract Uri getUriForSection(String section);

    //protected abstract String getSectionIDForSection(String section);

    protected abstract ReadPoint locateReadPoint(String section);

    public void load(File file) {
        this.file = file;
        data = getStorage(context, file);

        thisBookDir = getBookDir(context, file);
        thisBookDir.mkdirs();
        try {
            load();
        } catch (IOException e) {
            e.printStackTrace();
        }
        sectionIDs = getSectionIds();
        restoreCurrentSectionID();
    }

    public boolean hasDataDir() {
        return data!=null;
    }

    public Uri getFirstSection() {
        clearSectionOffset();
        currentSectionIDPos = 0;
        saveCurrentSectionID();
        return getUriForSectionID(sectionIDs.get(currentSectionIDPos));
    }

    public Uri getCurrentSection() {
        try {
            restoreCurrentSectionID();
            if (currentSectionIDPos >= sectionIDs.size()) {
                currentSectionIDPos = 0;
                saveCurrentSectionID();
            }

            if (sectionIDs.size() == 0) {
                return null;
            }
            return getUriForSectionID(sectionIDs.get(currentSectionIDPos));
        } catch (Throwable t) {
            Log.e("Booky", t.getMessage(), t);
            return null;
        }
    }

    public void setFontsize(int fontsize) {
        data.edit().putInt(FONTSIZE, fontsize).apply();
    }

    public int getFontsize() {
        return data.getInt(FONTSIZE, -1);
    }

    public void clearFontsize() {
        data.edit().remove(FONTSIZE).apply();
    }

    public void setSectionOffset(int offset) {
        data.edit().putInt(SECTION_ID_OFFSET, offset).apply();
    }

    public int getSectionOffset() {
        return data.getInt(SECTION_ID_OFFSET, -1);
    }

    private void clearSectionOffset() {
        data.edit().remove(SECTION_ID_OFFSET).apply();
    }


    public void setBackgroundColor(int color) {
        data.edit().putInt(BG_COLOR, color).apply();
    }

    public int getBackgroundColor() {
        return data.getInt(BG_COLOR, Integer.MAX_VALUE);
    }

    public void clearBackgroundColor() {
        data.edit().remove(BG_COLOR).apply();
    }


    public void setFlag(String key, boolean value) {
        data.edit().putBoolean(key, value).apply();
    }

    public boolean getFlag(String key, boolean value) {
        return data.getBoolean(key, value);
    }


    public Uri getNextSection() {
        try {
            if (currentSectionIDPos + 1 < sectionIDs.size()) {
                clearSectionOffset();
                currentSectionIDPos++;
                saveCurrentSectionID();
                return getUriForSectionID(sectionIDs.get(currentSectionIDPos));
            }
        } catch (Throwable t) {
            Log.e("Booky", t.getMessage(), t);
        }
        return null;
    }

    public Uri getPreviousSection() {
        try {
            if (currentSectionIDPos - 1 >= 0) {
                clearSectionOffset();
                currentSectionIDPos--;
                saveCurrentSectionID();
                return getUriForSectionID(sectionIDs.get(currentSectionIDPos));
            }
        } catch (Throwable t) {
            Log.e("Booky", t.getMessage(), t);
        }
        return null;
    }

    private Uri gotoSectionID(String id) {
        try {
            int pos = sectionIDs.indexOf(id);
            if (pos > -1 && pos < sectionIDs.size()) {
                currentSectionIDPos = pos;
                saveCurrentSectionID();
                return getUriForSectionID(sectionIDs.get(currentSectionIDPos));
            }
        } catch (Throwable t) {
            Log.e("Booky", t.getMessage(), t);
        }
        return null;
    }

    public Uri handleClickedLink(String clickedLink) {
        ReadPoint readPoint = locateReadPoint(clickedLink);

        if (readPoint!=null) {
            gotoSectionID(readPoint.getId());
            clearSectionOffset();
            return readPoint.getPoint();
        }
        return null;
    }


    private void saveCurrentSectionID() {
        Log.d("Book", "saving section " + currentSectionIDPos);
        data.edit().putInt(SECTION_ID, currentSectionIDPos).apply();
    }

    private void restoreCurrentSectionID() {
        currentSectionIDPos = data.getInt(SECTION_ID, currentSectionIDPos);
        Log.d("Book", "Loaded section " + currentSectionIDPos);
    }

    private static String makeOldFName(File file) {
        return file.getPath().replaceAll("[/\\\\]","_");
    }

    private static final String reservedChars = "[/\\\\:?\"'*|<>+\\[\\]()]";

    private static String makeFName(File file) {
        String fname = file.getPath().replaceAll(reservedChars,"_");
        if (fname.getBytes().length>60) {
            //for very long names, we take the first part and the last part and the crc.
            // should be unique.
            int len = 30;
            if (fname.length()<=len) {  //just in case I'm missing some utf bytes vs length weirdness here
                len = fname.length()-1;
            }
            fname = fname.substring(0,len) + fname.substring(fname.length()-len/2) + crc32(fname);
        }
        return fname;
    }

    private static long crc32(String input) {
        byte[] bytes = input.getBytes();
        Checksum checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length);
        return checksum.getValue();
    }

    //fix long/invalid filenames while maintaining those that somehow worked.
    private static String getProperFName(Context context, File file) {
        String fname;
        if (hasOldBookDir(context, file)) {
            fname = makeOldFName(file);
            Log.d("Book", "using old fname " + fname);
        } else {
            fname = makeFName(file);
            Log.d("Book", "using new fname " + fname);
        }
        return fname;
    }

    private static boolean hasOldBookDir(Context context, File file) {
        String subbook = "book" + makeOldFName(file);
        return new File(context.getFilesDir(), subbook).exists();
    }

    private static File getBookDir(Context context, File file) {
        String fname = getProperFName(context, file);
        String subbook = "book" + fname;
        return new File(context.getFilesDir(), subbook);
    }

    private static SharedPreferences getStorage(Context context, File file) {
        String fname = getProperFName(context, file);
        return context.getSharedPreferences(fname, Context.MODE_PRIVATE);
    }

    public static boolean remove(Context context, File file) {
        try {
            FsTools.deleteDir(getBookDir(context, file));
            String fname = getProperFName(context, file);
            if (Build.VERSION.SDK_INT >= 24) {
                return context.deleteSharedPreferences(fname);
            } else {
                return getStorage(context, file).edit().clear().commit();
            }
        } catch (Exception e) {
            Log.e("Book", e.getMessage(),e);
        }
        return false;
    }

    public boolean remove() {
        FsTools.deleteDir(getThisBookDir());
        return data.edit().clear().commit();
    }

    File getThisBookDir() {
        return thisBookDir;
    }

    public String getTitle() {
        return title;
    }

    protected void setTitle(String title) {
        this.title = title;
    }

    File getFile() {
        return file;
    }

    private void setFile(File file) {
        this.file = file;
    }


    public File getDataDir() {
        return dataDir;
    }

    protected Context getContext() {
        return context;
    }

    SharedPreferences getSharedPreferences() {
        return data;
    }



    public static String getFileExtensionRX() {
        return ".*\\.(epub|txt|html?)";
    }

    public static Book getBookHandler(Context context, String filename) throws IOException {
        Book book = null;
        if (filename.toLowerCase().endsWith(".epub")) {
            book = new EpubBook(context);
        } else if (filename.toLowerCase().endsWith(".txt")) {
            book = new TxtBook(context);
        } else if (filename.toLowerCase().endsWith(".html") || filename.toLowerCase().endsWith(".htm")) {
            book = new HtmlBook(context);
        }

        return book;

    }

    public static BookMetadata getBookMetaData(Context context, String filename) throws IOException {

        Book book = getBookHandler(context, filename);
        if (book!=null) {
            book.setFile(new File(filename));

            return book.getMetaData();
        }

        return null;

    }

    protected class ReadPoint {
        private String id;
        private Uri point;

        String getId() {
            return id;
        }

        void setId(String id) {
            this.id = id;
        }

        Uri getPoint() {
            return point;
        }

        void setPoint(Uri point) {
            this.point = point;
        }
    }
}


0x02

package com.quaap.bookymcbookface.book;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class BookMetadata {
    private String title;
    private String author;
    private String filename;

    private Map<String,String> alldata = new LinkedHashMap<>();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Map<String, String> getAlldata() {
        return alldata;
    }

    public void setAlldata(Map<String, String> alldata) {
        this.alldata = alldata;
    }
}


0x03

        package com.quaap.bookymcbookface.book;


        import android.content.Context;
        import android.content.SharedPreferences;
        import android.net.Uri;
        import android.util.Log;

        import org.w3c.dom.Document;
        import org.w3c.dom.NamedNodeMap;
        import org.w3c.dom.Node;
        import org.w3c.dom.NodeList;
        import org.xml.sax.InputSource;
        import org.xml.sax.SAXException;
        import org.xmlpull.v1.XmlPullParser;
        import org.xmlpull.v1.XmlPullParserFactory;

        import java.io.BufferedReader;
        import java.io.File;
        import java.io.FileNotFoundException;
        import java.io.FileReader;
        import java.io.IOException;
        import java.io.InputStreamReader;
        import java.util.ArrayList;
        import java.util.Collections;
        import java.util.HashMap;
        import java.util.Iterator;
        import java.util.LinkedHashMap;
        import java.util.List;
        import java.util.Map;
        import java.util.zip.ZipEntry;
        import java.util.zip.ZipFile;

        import javax.xml.namespace.NamespaceContext;
        import javax.xml.parsers.DocumentBuilder;
        import javax.xml.parsers.DocumentBuilderFactory;
        import javax.xml.parsers.ParserConfigurationException;
        import javax.xml.xpath.XPath;
        import javax.xml.xpath.XPathConstants;
        import javax.xml.xpath.XPathExpressionException;
        import javax.xml.xpath.XPathFactory;

        import com.quaap.bookymcbookface.Zip;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class EpubBook extends Book {

    private static final String META_PREFIX = "meta.";
    private static final String ORDERCOUNT = "ordercount";
    private static final String BOOK_CONTENT_DIR = "bookContentDir";
    private static final String ORDER = "order.";
    private static final String ITEM = "item.";
    private static final String TOCCOUNT = "toccount";
    private static final String TOC_LABEL = "toc.label.";
    private static final String TOC_CONTENT = "toc.content.";
    private static final String TOC = "toc";

    private File bookContentDir;

    public EpubBook(Context context) {
        super(context);
    }

    @Override
    protected void load() throws IOException {

        if (!getSharedPreferences().contains(ORDERCOUNT)) {
            for (File file: Zip.unzip(getFile(), getThisBookDir())) {
                Log.d("EPUB", "unzipped + " + file);
            }
            loadEpub();
        }

        SharedPreferences bookdat = getSharedPreferences();

        //Set<String> keys = bookdat.getAll().keySet();

        bookContentDir = new File(bookdat.getString(BOOK_CONTENT_DIR,""));
        int ocount = bookdat.getInt(ORDERCOUNT,0);

        for (int i=0; i<ocount; i++) {
            String item = bookdat.getString(ORDER + i, "");
            String file = bookdat.getString(ITEM + item, "");
            docFileOrder.add(item);
            docFiles.put(item, file);

            //Log.d("EPUB", "Item: " + item + ". File: " + file);
        }

        int toccount = bookdat.getInt(TOCCOUNT,0);

        for (int i=0; i<toccount; i++) {
            String label = bookdat.getString(TOC_LABEL + i, "");
            String point = bookdat.getString(TOC_CONTENT + i, "");

            tocPoints.put(point,label);
            // Log.d("EPUB", "TOC: " + label + ". File: " + point);

        }
    }

    @Override
    public Map<String,String> getToc() {
        return Collections.unmodifiableMap(tocPoints);
    }

    @Override
    protected List<String> getSectionIds() {
        return Collections.unmodifiableList(docFileOrder);
    }

    @Override
    protected Uri getUriForSectionID(String id) {

        return Uri.fromFile(new File(getFullBookContentDir(), docFiles.get(id)));
    }

    @Override
    protected ReadPoint locateReadPoint(String section) {
        ReadPoint point = null;

        if (section==null) return null;

        Uri suri = null;

        try {
            suri = Uri.parse(Uri.decode(section));
        } catch (Exception e) {
            Log.e("Epub", e.getMessage(), e);
        }

        if (suri==null) return null;

        if (suri.isRelative()) {
            suri = new Uri.Builder().scheme("file").path(getFullBookContentDir().getPath()).appendPath(suri.getPath()).fragment(suri.getFragment()).build();
        }

        String file = suri.getLastPathSegment();

        if (file==null) return null;

        String sectionID = null;

        for (Map.Entry<String,String> entry: docFiles.entrySet()) {
            if (file.equals(entry.getValue())) {
                sectionID = entry.getKey();
            }
        }

        if (sectionID!=null) {
            point = new ReadPoint();
            point.setId(sectionID);
            point.setPoint(suri);
        }

        return point;
    }



    private File getFullBookContentDir() {
        return new File(getThisBookDir(), bookContentDir.getPath());
    }


    private Map<String,String> metadata = new HashMap<>();
    private final Map<String,String> docFiles = new LinkedHashMap<>();
    private final List<String> docFileOrder = new ArrayList<>();

    private final Map<String,String> tocPoints = new LinkedHashMap<>();


    private void loadEpub() throws FileNotFoundException {

        List<String> rootFiles = getRootFilesFromContainer(new BufferedReader(new FileReader(new File(getThisBookDir(), "META-INF/container.xml"))));

        SharedPreferences.Editor bookdat = getSharedPreferences().edit();

        String bookContentDir = new File(rootFiles.get(0)).getParent();
        if (bookContentDir==null) bookContentDir = "";

        Map<String,?> dat = processBookDataFromRootFile(new BufferedReader(new FileReader(new File(getThisBookDir(),rootFiles.get(0)))));

        bookdat.putString(BOOK_CONTENT_DIR, bookContentDir);

        for (Map.Entry<String,?> entry: dat.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                bookdat.putString(entry.getKey(), (String) value);
            } else if (value instanceof Integer) {
                bookdat.putInt(entry.getKey(), (Integer) value);
            }
        }

        if (dat.get(TOC)!=null) {
            String fname = (String)dat.get(ITEM + dat.get(TOC));
            Log.d("EPUB", "tocfname = " + fname + " bookContentDir =" + bookContentDir);
            File tocfile = new File(new File(getThisBookDir(), bookContentDir), fname);
            Map<String, ?> tocDat = processToc(new BufferedReader(new FileReader(tocfile)));

            for (Map.Entry<String,?> entry: tocDat.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    bookdat.putString(entry.getKey(), (String) value);
                } else if (value instanceof Integer) {
                    bookdat.putInt(entry.getKey(), (Integer) value);
                }
            }
        }

        bookdat.apply();

    }

    @Override
    public String getTitle() {
        return getSharedPreferences().getString(META_PREFIX + "dc:title", "No title");
    }


    public BookMetadata getMetaData() throws IOException {


        try (ZipFile zipReader = new ZipFile(getFile())) {

            ZipEntry container = zipReader.getEntry("META-INF/container.xml");
            if (container == null) return null;

            List<String> rootFiles = getRootFilesFromContainer(new BufferedReader(new InputStreamReader(zipReader.getInputStream(container))));
            if (rootFiles.size() == 0) return null;

            ZipEntry content = zipReader.getEntry(rootFiles.get(0));
            if (content == null) return null;

            Map<String, ?> data = processBookDataFromRootFile(new BufferedReader(new InputStreamReader(zipReader.getInputStream(content))));
            if (data.size()==0) {
                Log.d("Epub", "No data for " + getFile());
            }

            Map<String, String> metadata = new LinkedHashMap<>();

            for (String key : data.keySet()) {
                if (key.startsWith(META_PREFIX)) {
                    metadata.put(key.substring(META_PREFIX.length()), data.get(key).toString());
                    // Log.d("META", key.substring(META_PREFIX.length()) + "=" + data.get(key).toString());
                }
            }

            BookMetadata mdata = new BookMetadata();
            mdata.setFilename(getFile().getPath());
            mdata.setTitle(metadata.get("dc:title"));
            mdata.setAuthor(metadata.get("dc:creator"));
            mdata.setAlldata(metadata);
            return mdata;

        }
    }


    private static List<String> getRootFilesFromContainer(BufferedReader containerxml) {

        List<String> rootFiles = new ArrayList<>();

        try {

            containerxml.mark(4);
            if ('\ufeff' != containerxml.read()) containerxml.reset(); // not the BOM marker

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(containerxml);

            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (xpp.getName().equals("rootfile")) {
                        for (int i = 0; i < xpp.getAttributeCount(); i++) {
                            if (xpp.getAttributeName(i).equals("full-path")) {
                                rootFiles.add(xpp.getAttributeValue(i));
                            }
                        }
                    }

                }
                eventType = xpp.next();
            }
        } catch (Exception e) {
            Log.e("BMBF", "Error parsing xml " + e, e);
        }

        return rootFiles;

    }


    private static Map<String,?> processBookDataFromRootFile(BufferedReader rootReader) {

        //SharedPreferences.Editor bookdat = getSharedPreferences().edit();

        Map<String,Object> bookdat = new LinkedHashMap<>();

        XPathFactory factory = XPathFactory.newInstance();
        DocumentBuilderFactory dfactory = DocumentBuilderFactory.newInstance();

        String toc = null;
        //String bookContentDir = null;


        try {

            rootReader.mark(4);
            if ('\ufeff' != rootReader.read()) rootReader.reset(); // not the BOM marker

            DocumentBuilder builder = dfactory.newDocumentBuilder();

            Document doc = builder.parse(new InputSource(rootReader));

            XPath xpath = factory.newXPath();


            xpath.setNamespaceContext(packnsc);

            Node root = (Node)xpath.evaluate("/package", doc.getDocumentElement(), XPathConstants.NODE);
            //Log.d("EPUB", root.getNodeName());

            {
                XPath metaPaths = factory.newXPath();
                metaPaths.setNamespaceContext(packnsc);
                NodeList metas = (NodeList) metaPaths.evaluate("metadata/*", root, XPathConstants.NODESET);
                for (int i = 0; i < metas.getLength(); i++) {
                    Node node = metas.item(i);
                    if (node == null) continue;
                    //Log.d("FFFF", node.getNodeName() + " " + node.getNodeValue());
                    String key=null;
                    String value = null;
                    NamedNodeMap attrs = node.getAttributes();
                    if (node.getNodeName().equals("meta") && attrs!=null) {
                        Node kn = attrs.getNamedItem("name");
                        if (kn!=null) key  = kn.getNodeValue();
                        Node kc = attrs.getNamedItem("content");
                        if (kc!=null) value = kc.getNodeValue();
                    } else {
                        key = node.getNodeName();
                        value = node.getTextContent();
                    }
                    //Log.d("EPB", "metadata: " + key+"="+value);
                    //metadata.put(key,value);
                    if (key!=null) {
                        bookdat.put(META_PREFIX + key, value);
                    }
                }
            }

            {
                XPath manifestPath = factory.newXPath();
                manifestPath.setNamespaceContext(packnsc);

                NodeList mani = (NodeList) manifestPath.evaluate("manifest/item", root, XPathConstants.NODESET);
                for (int i = 0; i < mani.getLength(); i++) {
                    Node node = mani.item(i);
                    if (node.getNodeName().equals("item")) {
                        String key;
                        String value;
                        NamedNodeMap attrs = node.getAttributes();
                        key = attrs.getNamedItem("id").getNodeValue();
                        value = attrs.getNamedItem("href").getNodeValue();
                        //docFiles.put(key,value);
                        bookdat.put(ITEM +key,value);
                        // Log.d("EPB", "manifest: " + key+"="+value);

                    }
                }
            }

            {
                XPath spinePath = factory.newXPath();
                spinePath.setNamespaceContext(packnsc);
                Node spine = (Node) spinePath.evaluate("spine", root, XPathConstants.NODE);
                NamedNodeMap sattrs = spine.getAttributes();
                toc = sattrs.getNamedItem(TOC).getNodeValue();

                bookdat.put(TOC, toc);
                //Log.d("EPB", "spine: toc=" + toc);

                NodeList spineitems = (NodeList) spinePath.evaluate("itemref", spine, XPathConstants.NODESET);
                for (int i = 0; i < spineitems.getLength(); i++) {
                    Node node = spineitems.item(i);
                    if (node.getNodeName().equals("itemref")) {
                        NamedNodeMap attrs = node.getAttributes();

                        String item = attrs.getNamedItem("idref").getNodeValue();

                        bookdat.put(ORDER +i, item);
                        //Log.d("EPB", "spine: " + item);

                        //docFileOrder.add(item);
                    }

                }
                bookdat.put(ORDERCOUNT, spineitems.getLength());
            }


        } catch (Exception e) {
            Log.e("BMBF", "Error parsing xml " + e.getMessage(), e);
        }

        return bookdat;
    }


    private static Map<String,?> processToc(BufferedReader tocReader) {
        Map<String,Object> bookdat = new LinkedHashMap<>();

        DocumentBuilderFactory dfactory = DocumentBuilderFactory.newInstance();
        XPathFactory factory = XPathFactory.newInstance();
        DocumentBuilder builder = null;
        try {
            builder = dfactory.newDocumentBuilder();

            tocReader.mark(4);
            if ('\ufeff' != tocReader.read()) tocReader.reset(); // not the BOM marker

            Document doc = builder.parse(new InputSource(tocReader));

            XPath tocPath = factory.newXPath();
            tocPath.setNamespaceContext(tocnsc);

            Node nav = (Node)tocPath.evaluate("/ncx/navMap", doc, XPathConstants.NODE);

            int total = readNavPoint(nav, tocPath, bookdat, 0);
            bookdat.put(TOCCOUNT, total);

        } catch (ParserConfigurationException | IOException | SAXException | XPathExpressionException e) {
            Log.e("BMBF", "Error parsing xml " + e.getMessage(), e);
        }
        return bookdat;
    }


    private static  int readNavPoint(Node nav, XPath tocPath, Map<String,Object> bookdat, int total) throws XPathExpressionException {

        NodeList list = (NodeList)tocPath.evaluate("navPoint", nav, XPathConstants.NODESET);
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            String label = tocPath.evaluate("navLabel/text/text()", node);
            String content = tocPath.evaluate("content/@src", node);
            bookdat.put(TOC_LABEL +total, label);
            bookdat.put(TOC_CONTENT +total, content);
            //Log.d("EPB", "toc: " + label + " " + content + " " + total);
            total++;
            total = readNavPoint(node, tocPath, bookdat, total);
        }
        return total;
    }


    private static final NamespaceContext tocnsc = new NamespaceContext() {
        @Override
        public String getNamespaceURI(String s) {
            return "http://www.daisy.org/z3986/2005/ncx/";
        }

        @Override
        public String getPrefix(String s) {
            return null;
        }

        @Override
        public Iterator getPrefixes(String s) {
            return null;
        }
    };

    private static final NamespaceContext packnsc = new NamespaceContext() {
        @Override
        public String getNamespaceURI(String s) {
            if (s!=null && s.equals("dc")) {
                return "http://purl.org/dc/elements/1.1/";
            }
            return "http://www.idpf.org/2007/opf";

        }

        @Override
        public String getPrefix(String s) {
            return null;
        }

        @Override
        public Iterator getPrefixes(String s) {
            return null;
        }
    };


}


0x04


        package com.quaap.bookymcbookface.book;

        import android.content.Context;
        import android.content.SharedPreferences;
        import android.net.Uri;
        import android.util.Log;

        import java.io.BufferedReader;
        import java.io.FileNotFoundException;
        import java.io.FileReader;
        import java.io.IOException;
        import java.io.Reader;
        import java.util.ArrayList;
        import java.util.LinkedHashMap;
        import java.util.List;
        import java.util.Map;
        import java.util.regex.Matcher;
        import java.util.regex.Pattern;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class HtmlBook extends Book {
    private static final String ORDERCOUNT = "ordercount";
    private static final String TOC_LABEL = "toc.label.";
    private static final String TOC_CONTENT = "toc.content.";
    private final List<String> l = new ArrayList<>();
    private Map<String,String> toc;

    public HtmlBook(Context context) {
        super(context);
    }

    @Override
    protected void load() throws IOException {
        if (!getFile().exists() || !getFile().canRead()) {
            throw new FileNotFoundException(getFile() + " doesn't exist or not readable");
        }

        toc = new LinkedHashMap<>();

        SharedPreferences bookdat = getSharedPreferences();
        if (bookdat.contains(ORDERCOUNT)) {
            int toccount = bookdat.getInt(ORDERCOUNT, 0);

            for (int i = 0; i < toccount; i++) {
                String label = bookdat.getString(TOC_LABEL + i, "");
                String point = bookdat.getString(TOC_CONTENT + i, "");

                toc.put(point, label);
                Log.d("EPUB", "TOC: " + label + ". File: " + point);

            }

        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(getFile()))) {
                int c = 0;
                String line;

                Pattern idlinkrx = Pattern.compile("<a\\s+[^>]*\\b(?i:name|id)=\"([^\"]+)\"[^>]*>(?:(.+?)</a>)?");
                Pattern hidlinkrx = Pattern.compile("<h[1-3]\\s+[^>]*\\bid=\"([^\"]+)\"[^>]*>(.+?)</h");

                SharedPreferences.Editor bookdatedit = bookdat.edit();

                while ((line = reader.readLine()) != null) {
                    String id = null;
                    String text = null;
                    Matcher t = idlinkrx.matcher(line);
                    if (t.find()) {
                        id = t.group(1);
                        text = t.group(2);
                    }
                    Matcher t2 = hidlinkrx.matcher(line);
                    if (t2.find()) {
                        id = t2.group(1);
                        text = t2.group(2);
                    }
                    if (id != null) {
                        if (text==null) text=id;
                        bookdatedit.putString(TOC_LABEL +c, text);
                        bookdatedit.putString(TOC_CONTENT +c, "#"+id);
                        toc.put("#"+id, text);
                        c++;
                    }


                }
                bookdatedit.putInt(ORDERCOUNT, c);

                bookdatedit.apply();
            }
        }

    }

    @Override
    public Map<String, String> getToc() {
        return toc;
    }

    @Override
    protected BookMetadata getMetaData() throws IOException {
        BookMetadata metadata = new BookMetadata();
        metadata.setFilename(getFile().getPath());

        try (Reader reader = new FileReader(getFile())) {

            char[] header = new char[8196];
            Pattern titlerx = Pattern.compile("(?is:<title.*?>\\s*(.+?)\\s*</title>)");

            boolean foundtitle = false;

            if(reader.read(header)>0) {
                String line = new String(header);
                Matcher tm = titlerx.matcher(line);
                if (tm.find()) {
                    metadata.setTitle(tm.group(1));
                    foundtitle = true;
                }

            }

            if (!foundtitle) {
                metadata.setTitle(getFile().getName());
            }
        }


        return metadata;
    }

    @Override
    protected List<String> getSectionIds() {

        l.add("1");
        return l;
    }

    @Override
    protected Uri getUriForSectionID(String id) {
        return Uri.fromFile(getFile());
    }

    @Override
    protected ReadPoint locateReadPoint(String section) {
        ReadPoint readPoint = new ReadPoint();
        readPoint.setId("1");

        Uri suri = Uri.parse(section);

        if (suri.isRelative()) {
            suri = new Uri.Builder().scheme("file").path(getFile().getPath()).fragment(suri.getFragment()).build();
        }

        readPoint.setPoint(suri);
        return readPoint;
    }


//    @Override
//    protected Uri getUriForSection(String section) {
//        return Uri.fromFile(getFile());
//    }
//
//    @Override
//    protected String getSectionIDForSection(String section) {
//        return "1";
//    }
//


}


0x05


        package com.quaap.bookymcbookface.book;

        import android.content.Context;
        import android.content.SharedPreferences;
        import android.net.Uri;
        import android.util.Log;

        import java.io.BufferedReader;
        import java.io.FileNotFoundException;
        import java.io.FileReader;
        import java.io.IOException;
        import java.io.Reader;
        import java.util.ArrayList;
        import java.util.LinkedHashMap;
        import java.util.List;
        import java.util.Map;
        import java.util.regex.Matcher;
        import java.util.regex.Pattern;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class HtmlBook extends Book {
    private static final String ORDERCOUNT = "ordercount";
    private static final String TOC_LABEL = "toc.label.";
    private static final String TOC_CONTENT = "toc.content.";
    private final List<String> l = new ArrayList<>();
    private Map<String,String> toc;

    public HtmlBook(Context context) {
        super(context);
    }

    @Override
    protected void load() throws IOException {
        if (!getFile().exists() || !getFile().canRead()) {
            throw new FileNotFoundException(getFile() + " doesn't exist or not readable");
        }

        toc = new LinkedHashMap<>();

        SharedPreferences bookdat = getSharedPreferences();
        if (bookdat.contains(ORDERCOUNT)) {
            int toccount = bookdat.getInt(ORDERCOUNT, 0);

            for (int i = 0; i < toccount; i++) {
                String label = bookdat.getString(TOC_LABEL + i, "");
                String point = bookdat.getString(TOC_CONTENT + i, "");

                toc.put(point, label);
                Log.d("EPUB", "TOC: " + label + ". File: " + point);

            }

        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader(getFile()))) {
                int c = 0;
                String line;

                Pattern idlinkrx = Pattern.compile("<a\\s+[^>]*\\b(?i:name|id)=\"([^\"]+)\"[^>]*>(?:(.+?)</a>)?");
                Pattern hidlinkrx = Pattern.compile("<h[1-3]\\s+[^>]*\\bid=\"([^\"]+)\"[^>]*>(.+?)</h");

                SharedPreferences.Editor bookdatedit = bookdat.edit();

                while ((line = reader.readLine()) != null) {
                    String id = null;
                    String text = null;
                    Matcher t = idlinkrx.matcher(line);
                    if (t.find()) {
                        id = t.group(1);
                        text = t.group(2);
                    }
                    Matcher t2 = hidlinkrx.matcher(line);
                    if (t2.find()) {
                        id = t2.group(1);
                        text = t2.group(2);
                    }
                    if (id != null) {
                        if (text==null) text=id;
                        bookdatedit.putString(TOC_LABEL +c, text);
                        bookdatedit.putString(TOC_CONTENT +c, "#"+id);
                        toc.put("#"+id, text);
                        c++;
                    }


                }
                bookdatedit.putInt(ORDERCOUNT, c);

                bookdatedit.apply();
            }
        }

    }

    @Override
    public Map<String, String> getToc() {
        return toc;
    }

    @Override
    protected BookMetadata getMetaData() throws IOException {
        BookMetadata metadata = new BookMetadata();
        metadata.setFilename(getFile().getPath());

        try (Reader reader = new FileReader(getFile())) {

            char[] header = new char[8196];
            Pattern titlerx = Pattern.compile("(?is:<title.*?>\\s*(.+?)\\s*</title>)");

            boolean foundtitle = false;

            if(reader.read(header)>0) {
                String line = new String(header);
                Matcher tm = titlerx.matcher(line);
                if (tm.find()) {
                    metadata.setTitle(tm.group(1));
                    foundtitle = true;
                }

            }

            if (!foundtitle) {
                metadata.setTitle(getFile().getName());
            }
        }


        return metadata;
    }

    @Override
    protected List<String> getSectionIds() {

        l.add("1");
        return l;
    }

    @Override
    protected Uri getUriForSectionID(String id) {
        return Uri.fromFile(getFile());
    }

    @Override
    protected ReadPoint locateReadPoint(String section) {
        ReadPoint readPoint = new ReadPoint();
        readPoint.setId("1");

        Uri suri = Uri.parse(section);

        if (suri.isRelative()) {
            suri = new Uri.Builder().scheme("file").path(getFile().getPath()).fragment(suri.getFragment()).build();
        }

        readPoint.setPoint(suri);
        return readPoint;
    }


//    @Override
//    protected Uri getUriForSection(String section) {
//        return Uri.fromFile(getFile());
//    }
//
//    @Override
//    protected String getSectionIDForSection(String section) {
//        return "1";
//    }
//


}

0x06
        package com.quaap.bookymcbookface.book;

        import android.content.Context;
        import android.net.Uri;


        import java.io.BufferedReader;
        import java.io.File;
        import java.io.FileNotFoundException;
        import java.io.FileReader;
        import java.io.FileWriter;
        import java.io.IOException;
        import java.io.Writer;
        import java.util.ArrayList;
        import java.util.LinkedHashMap;
        import java.util.List;
        import java.util.Map;
        import java.util.regex.Matcher;
        import java.util.regex.Pattern;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class TxtBook extends Book {
    private final List<String> l = new ArrayList<>();

    private final Map<String,String> toc = new LinkedHashMap<>();

    public TxtBook(Context context) {
        super(context);
    }

    @Override
    protected void load() throws IOException {
        if (!getFile().exists() || !getFile().canRead()) {
            throw new FileNotFoundException(getFile() + " doesn't exist or not readable");
        }
        File outFile = getBookFile();

        if (!outFile.exists()) {

            try (BufferedReader reader = new BufferedReader(new FileReader(getFile()))) {
                try (Writer out = new FileWriter(outFile)) {
                    StringBuilder para = new StringBuilder(4096);
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.matches("^\\s*$")) {
                            para.append(System.lineSeparator());
                            para.append(System.lineSeparator());
                            out.write(para.toString());
                            para.delete(0, para.length());
                        } else {
                            para.append(line);
                            if (!line.matches(".*\\s+$")) {
                                para.append(" ");
                            }
                            //if (line.matches("[.?!\"]\\s*$")) {
                            //    para.append(System.lineSeparator());
                            //}
                        }
                    }
                }
            }
        }

    }


    private File getBookFile() {
        return new File(getThisBookDir(), getFile().getName());
    }

    @Override
    public Map<String, String> getToc() {
        return toc;
    }

    @Override
    protected BookMetadata getMetaData() throws IOException {
        BookMetadata metadata = new BookMetadata();
        metadata.setFilename(getFile().getPath());

        try (BufferedReader reader = new BufferedReader(new FileReader(getFile()))) {
            int c = 0;
            String line;
            Pattern titlerx = Pattern.compile("^\\s*(?i:title)[:= \\t]+(.+)");
            Pattern authorrx = Pattern.compile("^\\s*(?i:author|by)[:= \\t]+(.{3,26})\\s*$");
            Pattern titleauthorrx =
                    Pattern.compile("^(?xi: " +
                            "\\s*(?:The \\s+ Project \\s+ Gutenberg \\s+ EBook \\s+ of \\s+)? " +
                            "(.+),? " +
                            "\\s+ (?:translated\\s+|written\\s+)? by \\s+ " +
                            "(.{3,26}) " +
                            " )$");

            boolean foundtitle = false;
            boolean foundauthor = false;
            String ptitle = null;
            String pauthor = null;

            String firstline = reader.readLine();

            line = firstline;

            if (line!=null) {
                Matcher tam = titleauthorrx.matcher(line);
                if (tam.find()) {
                    ptitle = tam.group(1);
                    pauthor = tam.group(2);
                }

                do {

                    Matcher tm = titlerx.matcher(line);
                    if (!foundtitle && tm.find()) {
                        metadata.setTitle(tm.group(1));
                        foundtitle = true;
                    }
                    Matcher am = authorrx.matcher(line);
                    if (!foundauthor && am.find()) {
                        metadata.setAuthor(am.group(1));
                        foundauthor = true;
                    }
                    if (c++ > 50 || foundauthor && foundtitle) {
                        break;
                    }

                } while ((line = reader.readLine()) != null);

                if (!foundtitle && ptitle != null) {
                    metadata.setTitle(ptitle);
                    foundtitle = true;
                }
                if (!foundauthor && pauthor != null) {
                    metadata.setAuthor(pauthor);
                }

                if (!foundtitle) {
                    metadata.setTitle(getFile().getName() + " " + firstline);
                }
            }
        }

        //metadata.setTitle(getFile().getName());
        return metadata;
    }

    @Override
    protected List<String> getSectionIds() {

        l.add("1");
        return l;
    }

    @Override
    protected Uri getUriForSectionID(String id) {
        return Uri.fromFile(getBookFile());
    }

    protected ReadPoint locateReadPoint(String section) {
        ReadPoint readPoint = new ReadPoint();
        readPoint.setId("1");
        readPoint.setPoint(Uri.parse(section));
        return readPoint;
    }



//    @Override
//    protected Uri getUriForSection(String section) {
//        return Uri.fromFile(getBookFile());
//    }
//
//    @Override
//    protected String getSectionIDForSection(String section) {
//        return "1";
//    }



}
0x07


        package com.quaap.bookymcbookface;

        import android.content.Context;
        import android.support.annotation.NonNull;
        import android.support.v7.widget.RecyclerView;
        import android.view.LayoutInflater;
        import android.view.View;
        import android.view.ViewGroup;
        import android.widget.TextView;

        import java.util.List;


public class BookAdapter extends  RecyclerView.Adapter<BookAdapter.BookViewHolder> {
    private List<Integer> mBookIds;
    private BookDb mDB;
    private Context mContext;

    private View.OnClickListener mOnClickListener;
    private View.OnLongClickListener mOnLongClickListener;

    static class BookViewHolder extends RecyclerView.ViewHolder {

        ViewGroup mBookEntry;
        TextView mTitleView;
        TextView mAuthorView;
        TextView mStatusView;
        BookViewHolder(ViewGroup listEntry) {
            super(listEntry);
            mBookEntry = listEntry;
            mTitleView = listEntry.findViewById(R.id.book_title);
            mAuthorView = listEntry.findViewById(R.id.book_author);
            mStatusView = listEntry.findViewById(R.id.book_status);
        }
    }

    // Provide a suitable constructor (depends on the kind of dataset)
    public BookAdapter(Context context, BookDb db, List<Integer> bookIds) {
        mContext = context;
        mBookIds = bookIds;
        mDB = db;
        setHasStableIds(true);
    }


    public void setOnClickListener(View.OnClickListener onClickListener) {
        mOnClickListener = onClickListener;
    }

    public void setOnLongClickListener(View.OnLongClickListener onLongClickListener) {
        mOnLongClickListener = onLongClickListener;

    }

    public void notifyItemIdRemoved(long id) {
        int pos = mBookIds.indexOf((int)id);
        if (pos>=0) {
            mBookIds.remove(pos);
            notifyItemRemoved(pos);
        }

    }

    public void notifyItemIdChanged(long id) {
        int pos = mBookIds.indexOf((int)id);
        if (pos>=0) {
            notifyItemChanged(pos);
        }

    }

    public void setBooks(List<Integer> bookIds) {
        int size = mBookIds.size();
        mBookIds.clear();
        notifyItemRangeRemoved(0, size);
        mBookIds.addAll(bookIds);
        notifyItemRangeInserted(0, mBookIds.size());

    }

    @Override
    public long getItemId(int position) {
        return mBookIds.get(position);
    }

    // Create new views (invoked by the layout manager)
    @NonNull
    @Override
    public BookAdapter.BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewGroup listEntry = (ViewGroup)LayoutInflater.from(parent.getContext()).inflate(R.layout.book_list_item, parent, false);

        return new BookViewHolder(listEntry);
    }

    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
        int bookid = mBookIds.get(position);
        BookDb.BookRecord book = mDB.getBookRecord(bookid);

        if (book != null && book.filename != null) {
            holder.mTitleView.setText(BookListActivity.maxlen(book.title, 120));
            holder.mAuthorView.setText(BookListActivity.maxlen(book.author, 50));

            long lastread = book.lastread;
            long time = lastread;

            int text;
            if (book.status==BookDb.STATUS_DONE) {
                text = R.string.book_status_completed;
            } else if (book.status==BookDb.STATUS_LATER) {
                time = 0;
                text = R.string.book_status_later;
            } else if (lastread>0 && book.status==BookDb.STATUS_STARTED) {
                text = R.string.book_viewed_on;
            } else {
                time = book.added;
                text = R.string.book_added_on;
            }

            CharSequence rtime = android.text.format.DateUtils.getRelativeTimeSpanString(time);

            holder.mStatusView.setTextSize(12);

            if (text==R.string.book_viewed_on) {
                holder.mStatusView.setTextSize(14);
            }
            holder.mStatusView.setText(mContext.getString(text, rtime));


            holder.mBookEntry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (mOnClickListener!=null) {
                        mOnClickListener.onClick(view);
                    }
                }
            });

        } else {
            holder.mTitleView.setText("Error with " + bookid);
            holder.mAuthorView.setText("Error");
            holder.mStatusView.setText("");
        }

        holder.mBookEntry.setTag(bookid);
        holder.mBookEntry.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (mOnLongClickListener!=null) {
                    return mOnLongClickListener.onLongClick(view);
                }
                return false;
            }
        });
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return mBookIds.size();
    }
}




0x07


        package com.quaap.bookymcbookface;

        import android.content.ContentValues;
        import android.content.Context;
        import android.database.Cursor;
        import android.database.sqlite.SQLiteDatabase;
        import android.database.sqlite.SQLiteOpenHelper;
        import android.support.annotation.NonNull;

        import java.util.ArrayList;
        import java.util.LinkedHashMap;
        import java.util.List;
        import java.util.Map;
        import java.util.regex.Matcher;
        import java.util.regex.Pattern;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class BookDb extends SQLiteOpenHelper {

    private final static String DBNAME = "bookdb";
    private final static int DBVERSION = 2;

    private final static String BOOK_TABLE = "book";
    private final static String BOOK_ID = "id";
    private final static String BOOK_TITLE = "title";
    private final static String BOOK_LIB_TITLE = "libtitle";
    private final static String BOOK_AUTHOR = "author";
    private final static String BOOK_LIB_AUTHOR = "libauthor";
    private final static String BOOK_FILENAME = "filename";
    private final static String BOOK_ADDED = "added";
    private final static String BOOK_LASTREAD = "lastread";
    private final static String BOOK_STATUS = "status";


    private final static String WEBS_TABLE = "webs";
    private final static String WEBS_NAME = "name";
    private final static String WEBS_URL = "url";

    private final Context context;

    private final Pattern authorRX;
    private final Pattern titleRX;

    public final static int STATUS_DONE = 128;
    public final static int STATUS_LATER = 32;
    public final static int STATUS_STARTED = 8;
    public final static int STATUS_NONE = 0;
    public final static int STATUS_ANY = -1;
    public final static int STATUS_SEARCH = -2;



    public BookDb(Context context) {
        super(context, DBNAME, null, DBVERSION);
        this.context = context;

        String namePrefixRX="sir|lady|rev(?:erend)?|doctor|dr|mr|ms|mrs|miss";
        String nameSuffixRX="jr|sr|\\S{1,5}\\.d|[jm]\\.?d|[IVX]+|1st|2nd|3rd|esq";
        String nameInfixRX="V[ao]n|De|St\\.?";

        authorRX = Pattern.compile("^\\s*(?:(?i:" + namePrefixRX + ")\\.?\\s+)? (.+?) (?:\\s+|d')?((?:(?:" + nameInfixRX + ")\\s+)? \\S+ (?:\\s+(?i:" + nameSuffixRX + ")\\.?)?)$", Pattern.COMMENTS);
        titleRX = Pattern.compile("^(a|an|the|la|el|le|eine?|der|die)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createbooktable =
                "create table " + BOOK_TABLE + "( " +
                        BOOK_ID + " INTEGER PRIMARY KEY," +
                        BOOK_TITLE + " TEXT," +
                        BOOK_LIB_TITLE + " TEXT," +
                        BOOK_AUTHOR + " TEXT," +
                        BOOK_LIB_AUTHOR + " TEXT," +
                        BOOK_FILENAME + " TEXT," +
                        BOOK_ADDED    + " INTEGER," +
                        BOOK_LASTREAD + " INTEGER," +
                        BOOK_STATUS  + " INTEGER" +
                        ")";
        db.execSQL(createbooktable);

        String [] indexcolums = {BOOK_LIB_TITLE, BOOK_LIB_AUTHOR, BOOK_FILENAME, BOOK_ADDED, BOOK_LASTREAD};

        for (String col: indexcolums) {
            db.execSQL("create index ind_" + col + " on " + BOOK_TABLE + " (" + col + ")");
        }

        String createwebstable =
                "create table " + WEBS_TABLE + "( " +
                        WEBS_URL + " TEXT PRIMARY KEY," +
                        WEBS_NAME + " TEXT" +
                        ")";
        db.execSQL(createwebstable);

        String [] wnames = context.getResources().getStringArray(R.array.getbook_names);
        String [] wurls = context.getResources().getStringArray(R.array.getbook_urls);

        for (int i=0; i<wnames.length; i++) {
            addWebsite(db, wnames[i], wurls[i]);
        }


    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion<2) {
            db.execSQL("alter table " + BOOK_TABLE + " add column " + BOOK_STATUS + " INTEGER");

            ContentValues data = new ContentValues();
            data.put(BOOK_STATUS, STATUS_NONE);
            db.update(BOOK_TABLE,data,null, null);

            data = new ContentValues();
            data.put(BOOK_STATUS, STATUS_STARTED);
            db.update(BOOK_TABLE,data,BOOK_LASTREAD + ">0", null);
        }

    }


    public boolean containsBook(String filename) {
        SQLiteDatabase db = this.getReadableDatabase();

        try (Cursor bookcursor = db.query(BOOK_TABLE,new String[] {BOOK_ID},BOOK_FILENAME + "=?", new String[] {filename}, null, null, null)) {

            return bookcursor.moveToNext();
        }

    }

    public boolean removeBook(String filename) {
        SQLiteDatabase db = this.getWritableDatabase();

        return db.delete(BOOK_TABLE, BOOK_FILENAME + "=?", new String[] {filename})>0;
    }

    public boolean removeBook(int id) {
        SQLiteDatabase db = this.getWritableDatabase();

        return db.delete(BOOK_TABLE, BOOK_ID + "=?", new String[] {""+id})>0;
    }

    public int addBook(String filename, String title, String author) {
        return addBook(filename, title, author, System.currentTimeMillis());
    }

    public int addBook(String filename, String title, String author, long dateadded) {

        if (filename==null || containsBook(filename)) return -1;

        if (title==null || title.trim().length()==0) title=filename.replaceAll(".*/","");
        if (author==null || author.trim().length()==0) author="Unknown";

        String libtitle = title.toLowerCase();
        {
            Matcher titlematch = titleRX.matcher(libtitle);
            if (titlematch.find()) {
                libtitle = titlematch.group(2) + ", " + titlematch.group(1);
            }
        }

        String libauthor = author;

        if (!libauthor.contains(",")) {


            Matcher authmatch = authorRX.matcher(libauthor);
            if (authmatch.find()) {
                libauthor = authmatch.group(2) + ", " + authmatch.group(1);
            }
        }
        libauthor = libauthor.toLowerCase();

        //Log.d("AddBook", "libauthor=" + libauthor);

        SQLiteDatabase db = this.getWritableDatabase();

        ContentValues data = new ContentValues();
        data.put(BOOK_TITLE, title);
        data.put(BOOK_LIB_TITLE, libtitle);
        data.put(BOOK_AUTHOR, author);
        data.put(BOOK_LIB_AUTHOR, libauthor);
        data.put(BOOK_FILENAME, filename);
        data.put(BOOK_ADDED, dateadded);
        data.put(BOOK_LASTREAD, -1);
        data.put(BOOK_STATUS, STATUS_NONE);

        return (int)db.insert(BOOK_TABLE,null, data);

    }

    public void updateLastRead(int id, long lastread) {
        SQLiteDatabase db = this.getWritableDatabase();

        ContentValues data = new ContentValues();
        data.put(BOOK_LASTREAD, lastread);
        db.update(BOOK_TABLE, data, BOOK_ID + "=?", new String[]{ id + ""});

        // Only change the status if it is NONE
        data = new ContentValues();
        data.put(BOOK_STATUS, STATUS_STARTED);
        db.update(BOOK_TABLE, data,BOOK_ID + "=? and " + BOOK_STATUS + "=" + STATUS_NONE, new String[]{ id + ""});
    }

    public void updateStatus(int id, int status) {
        SQLiteDatabase db = this.getWritableDatabase();

        ContentValues data = new ContentValues();
        data.put(BOOK_STATUS, status);

        db.update(BOOK_TABLE, data, BOOK_ID + "=?", new String[]{ id + ""});
    }


    public BookRecord getBookRecord(int id) {
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor bookscursor = db.query(BOOK_TABLE, new String[] {BOOK_ID, BOOK_FILENAME, BOOK_TITLE, BOOK_AUTHOR, BOOK_LASTREAD, BOOK_ADDED, BOOK_STATUS}, BOOK_ID + "=?", new String[] {""+id}, null, null, null)) {

            if (bookscursor.moveToNext()) {
                return getBookRecord(bookscursor);
            }
        }
        return null;
    }

    public long getLastReadTime(int id) {
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor bookscursor = db.query(BOOK_TABLE, new String[] {BOOK_LASTREAD}, BOOK_ID + "=?", new String[] {""+id}, null, null, null)) {

            if (bookscursor.moveToNext()) {
                return bookscursor.getLong(bookscursor.getColumnIndex(BOOK_LASTREAD));
            }
        }
        return -1;
    }


    public long getAddedTime(int id) {
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor bookscursor = db.query(BOOK_TABLE, new String[] {BOOK_ADDED}, BOOK_ID + "=?", new String[] {""+id}, null, null, null)) {

            if (bookscursor.moveToNext()) {
                return bookscursor.getLong(bookscursor.getColumnIndex(BOOK_ADDED));
            }
        }
        return -1;
    }


    public int getStatus(int id) {
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor bookscursor = db.query(BOOK_TABLE, new String[] {BOOK_STATUS}, BOOK_ID + "=?", new String[] {""+id}, null, null, null)) {

            if (bookscursor.moveToNext()) {
                return bookscursor.getInt(bookscursor.getColumnIndex(BOOK_STATUS));
            }
        }
        return 0;
    }


    public int getMostRecentlyRead() {
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor bookscursor =
                     db.rawQuery(
                             "select " + BOOK_ID + " from " + BOOK_TABLE +
                                     " where " + BOOK_LASTREAD +
                                     " = (select max(" + BOOK_LASTREAD +") from " + BOOK_TABLE + " where " + BOOK_LASTREAD + ">0) and " + BOOK_STATUS +"=" + STATUS_STARTED, null)) {

            if (bookscursor.moveToNext()) {
                return bookscursor.getInt(bookscursor.getColumnIndex(BOOK_ID));
            }
        }
        return -1;
    }

    @NonNull
    private BookRecord getBookRecord(Cursor bookscursor) {
        BookRecord br = new BookRecord();
        br.id = bookscursor.getInt(bookscursor.getColumnIndex(BOOK_ID));
        br.filename = bookscursor.getString(bookscursor.getColumnIndex(BOOK_FILENAME));
        br.title = bookscursor.getString(bookscursor.getColumnIndex(BOOK_TITLE));
        br.author = bookscursor.getString(bookscursor.getColumnIndex(BOOK_AUTHOR));
        br.lastread = bookscursor.getLong(bookscursor.getColumnIndex(BOOK_LASTREAD));
        br.added = bookscursor.getLong(bookscursor.getColumnIndex(BOOK_ADDED));
        br.status = bookscursor.getInt(bookscursor.getColumnIndex(BOOK_STATUS));
        return br;
    }

    public List<BookRecord> getBooks(SortOrder sortOrder) {
        SQLiteDatabase db = this.getReadableDatabase();

        List<BookRecord> books = new ArrayList<>();

        String orderby = BOOK_ADDED;
        switch (sortOrder) {
            case Title: orderby = BOOK_LIB_TITLE; break;
            case Author: orderby = BOOK_LIB_AUTHOR; break;
        }

        try (Cursor bookscursor = db.query(BOOK_TABLE,new String[] {BOOK_ID, BOOK_FILENAME, BOOK_TITLE, BOOK_AUTHOR, BOOK_LASTREAD, BOOK_ADDED},null, null, null, null, orderby)) {

            while (bookscursor.moveToNext()) {
                BookRecord br = getBookRecord(bookscursor);
                books.add(br);
            }
        }

        return books;
    }

    public List<Integer> getBookIds(SortOrder sortOrder, int status) {
        SQLiteDatabase db = this.getReadableDatabase();

        List<Integer> books = new ArrayList<>();

        String where = null;
        if (status>=0) {
            where = BOOK_STATUS + "=" + status;
        }
        //System.out.println("where: " + where);

        String orderby = BOOK_STATUS + ", 2 desc, " + BOOK_LIB_TITLE + " asc";
        switch (sortOrder) {
            case Title: orderby = BOOK_LIB_TITLE + ", 2 desc"; break;
            case Author: orderby = BOOK_LIB_AUTHOR + ", " + BOOK_LIB_TITLE + ", 2 desc"; break;
            case Added: orderby = BOOK_ADDED + " desc, " + BOOK_LIB_TITLE + ", " + BOOK_LIB_AUTHOR ; break;
        }

        try (Cursor bookscursor = db.query(BOOK_TABLE,new String[] {BOOK_ID, BOOK_ADDED + "/80000"}, where, null, null, null, orderby)) {

            while (bookscursor.moveToNext()) {
                books.add(bookscursor.getInt(bookscursor.getColumnIndex(BOOK_ID)));
                //System.out.println(bookscursor.getInt(bookscursor.getColumnIndex(BOOK_STATUS)));
            }
        }

        return books;
    }

    public List<Integer> searchBooks(String text, boolean title, boolean author) {
        SQLiteDatabase db = this.getReadableDatabase();

        List<Integer> books = new ArrayList<>();

        String whereclause = null;

        List<String> whereargs = new ArrayList<>();
        String orderby = "2";

        if (title) {
            whereclause = BOOK_LIB_TITLE + " like ?";
            whereargs.add("%" + text + "%");
            orderby += "," + BOOK_LIB_TITLE;
        }

        if (author) {
            if (whereclause!=null) {
                whereclause += " or ";
            } else {
                whereclause = "";
            }
            whereclause += BOOK_LIB_AUTHOR + " like ?";
            whereargs.add("%" + text + "%");
            orderby += "," + BOOK_LIB_AUTHOR;
        }


        try (Cursor bookscursor = db.query(BOOK_TABLE,new String[] {BOOK_ID, BOOK_ADDED + "/90000"},
                whereclause, whereargs.toArray(new String[whereargs.size()])
                , null, null, orderby)) {

            while (bookscursor.moveToNext()) {
                books.add(bookscursor.getInt(bookscursor.getColumnIndex(BOOK_ID)));
            }
        }

        return books;
    }


    public class BookRecord {
        public int id;
        public String filename;
        public String title;
        public String author;
        public long lastread;
        public long added;
        public int status;

    }


//    String createwebstable =
//            "create table " + WEBS_TABLE + "( " +
//                    WEBS_URL + " TEXT PRIMARY KEY," +
//                    WEBS_NAME + " TEXT" +
//                    ")";


    public int addWebsite(String name, String url) {
        SQLiteDatabase db = this.getWritableDatabase();
        return addWebsite(db, name, url);
    }

    private int addWebsite(SQLiteDatabase db, String name, String url) {


        ContentValues data = new ContentValues();
        data.put(WEBS_NAME, name);
        data.put(WEBS_URL, url);

        return (int)db.insert(WEBS_TABLE,null, data);

    }

    public Map<String,String> getWebSites() {

        Map<String,String> webs = new LinkedHashMap<>();
        SQLiteDatabase db = this.getReadableDatabase();

        try (Cursor cursor = db.query(WEBS_TABLE,new String[] {WEBS_URL, WEBS_NAME},null, null, null, null, WEBS_NAME)) {

            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndex(WEBS_NAME));
                String url = cursor.getString(cursor.getColumnIndex(WEBS_URL));
                webs.put(url, name);
            }
        }
        return webs;
    }

    public boolean deleteWebSite(String url) {
        SQLiteDatabase db = this.getWritableDatabase();

        return db.delete(WEBS_TABLE, WEBS_URL + "=?", new String[] {url})>0;
    }
}




package com.quaap.bookymcbookface;

        import android.Manifest;
        import android.app.AlertDialog;
        import android.content.Context;
        import android.content.DialogInterface;
        import android.content.Intent;
        import android.content.SharedPreferences;
        import android.content.pm.PackageManager;
        import android.content.pm.ShortcutInfo;
        import android.content.pm.ShortcutManager;
        import android.graphics.drawable.Icon;
        import android.os.AsyncTask;
        import android.os.Bundle;
        import android.os.Handler;
        import android.os.Message;
        import android.support.annotation.NonNull;
        import android.support.v4.app.ActivityCompat;
        import android.support.v4.content.ContextCompat;
        import android.support.v7.app.AppCompatActivity;
        import android.support.v7.widget.DefaultItemAnimator;
        import android.support.v7.widget.LinearLayoutManager;
        import android.support.v7.widget.RecyclerView;
        import android.text.Editable;
        import android.text.SpannableString;
        import android.text.TextWatcher;
        import android.text.method.LinkMovementMethod;
        import android.text.util.Linkify;
        import android.util.Log;
        import android.view.KeyEvent;
        import android.view.LayoutInflater;
        import android.view.Menu;
        import android.view.MenuInflater;
        import android.view.MenuItem;
        import android.view.MotionEvent;
        import android.view.View;
        import android.view.inputmethod.EditorInfo;
        import android.view.inputmethod.InputMethodManager;
        import android.widget.EditText;
        import android.widget.PopupMenu;
        import android.widget.RadioButton;
        import android.widget.TextView;
        import android.widget.Toast;

        import java.io.File;
        import java.lang.ref.WeakReference;
        import java.util.ArrayList;
        import java.util.Collections;
        import java.util.List;

        import com.quaap.bookymcbookface.book.Book;
        import com.quaap.bookymcbookface.book.BookMetadata;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class BookListActivity extends AppCompatActivity {

    private static final String SORTORDER_KEY = "sortorder";
    private static final String LASTSHOW_STATUS_KEY = "LastshowStatus";
    private static final String STARTWITH_KEY = "startwith";

    private static final int STARTLASTREAD = 1;
    private static final int STARTOPEN = 2;
    private static final int STARTALL = 3;

    private static final String ACTION_SHOW_OPEN = "com.quaap.bookymcbookface.SHOW_OPEN_BOOKS";
    private static final String ACTION_SHOW_UNREAD = "com.quaap.bookymcbookface.SHOW_UNREAD_BOOKS";
    public static final String ACTION_SHOW_LAST_STATUS = "com.quaap.bookymcbookface.SHOW_LAST_STATUS";

    private SharedPreferences data;

    private BookAdapter bookAdapter;

    private BookListAdderHandler viewAdder;
    private TextView tv;

    private BookDb db;
    private int recentread;
    private boolean showingSearch;

    private int showStatus = BookDb.STATUS_ANY;

    public final String SHOW_STATUS = "showStatus";

    public final static String prefname = "booklist";

    private boolean openLastread = false;
    private static boolean alreadyStarted=false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_list);

        tv = findViewById(R.id.progress_text);
        checkStorageAccess(false);

        data = getSharedPreferences(prefname, Context.MODE_PRIVATE);

        viewAdder = new BookListAdderHandler(this);

        if (!data.contains(SORTORDER_KEY)) {
            setSortOrder(SortOrder.Default);
        }

        //getApplicationContext().deleteDatabase(BookDb.DBNAME);

        db = BookyApp.getDB(this);

        RecyclerView listHolder = findViewById(R.id.book_list_holder);
        listHolder.setLayoutManager(new LinearLayoutManager(this));
        listHolder.setItemAnimator(new DefaultItemAnimator());

        bookAdapter = new BookAdapter(this, db, new ArrayList<Integer>());
        bookAdapter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                readBook((int)view.getTag());
            }
        });
        bookAdapter.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                longClickBook(view);
                return false;
            }
        });

        listHolder.setAdapter(bookAdapter);

        processIntent(getIntent());

        //Log.d("BOOKLIST", "onCreate end");

    }

    @Override
    protected void onNewIntent(Intent intent) {
        //Log.d("BOOKLIST", "onNewIntent");
        super.onNewIntent(intent);
        processIntent(intent);
    }

    private void processIntent(Intent intent) {

        recentread = db.getMostRecentlyRead();

        showStatus = BookDb.STATUS_ANY;

        openLastread = false;

        boolean hadSpecialOpen = false;
        //Intent intent = getIntent();
        if (intent != null) {
            if (intent.getAction() != null) {
                switch (intent.getAction()) {
                    case ACTION_SHOW_OPEN:
                        showStatus = BookDb.STATUS_STARTED;
                        hadSpecialOpen = true;
                        break;
                    case ACTION_SHOW_UNREAD:
                        showStatus = BookDb.STATUS_NONE;
                        hadSpecialOpen = true;
                        break;
                    case ACTION_SHOW_LAST_STATUS:
                        showStatus = data.getInt(LASTSHOW_STATUS_KEY, BookDb.STATUS_ANY);
                        hadSpecialOpen = true;
                        break;
                }

            }
        }

        if (!hadSpecialOpen){

            switch (data.getInt(STARTWITH_KEY, STARTLASTREAD)) {
                case STARTLASTREAD:
                    if (recentread!=-1 && data.getBoolean(ReaderActivity.READEREXITEDNORMALLY, true)) openLastread = true;
                    break;
                case STARTOPEN:
                    showStatus = BookDb.STATUS_STARTED; break;
                case STARTALL:
                    showStatus = BookDb.STATUS_ANY;
            }
        }


    }

    @Override
    protected void onResume() {
        //Log.d("BOOKLIST", "onResume");
        super.onResume();
        if (openLastread) {
            openLastread = false;
            viewAdder.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        BookDb.BookRecord book = db.getBookRecord(recentread);
                        getReader(book, true);
                        //finish();
                    } catch (Exception e) {
                        data.edit().putInt(STARTWITH_KEY, STARTALL).apply();
                    }
                }
            }, 200);
        } else {
            populateBooks(showStatus);
        }
    }

    @Override
    public void onBackPressed() {
        if (showingSearch || showStatus!=BookDb.STATUS_ANY) {
            setTitle(R.string.app_name);
            populateBooks();
            showingSearch = false;
        } else {
            super.onBackPressed();
        }
    }

    private void setSortOrder(SortOrder sortOrder) {
        data.edit().putString(SORTORDER_KEY,sortOrder.name()).apply();
    }

    @NonNull
    private SortOrder getSortOrder() {

        try {
            return SortOrder.valueOf(data.getString(SORTORDER_KEY, SortOrder.Default.name()));
        } catch (IllegalArgumentException e) {
            Log.e("Booklist", e.getMessage(), e);
            return SortOrder.Default;
        }
    }

    private void populateBooks() {
        populateBooks(BookDb.STATUS_ANY);
    }

    private void populateBooks(int status) {
        //Log.d("BOOKLIST", "populateBooks " + status);
        showStatus = status;
        data.edit().putInt(LASTSHOW_STATUS_KEY, showStatus).apply();

        boolean showRecent = false;
        int title = R.string.app_name;
        switch (status) {
            case BookDb.STATUS_SEARCH:
                String lastSearch = data.getString("__LAST_SEARCH_STR__","");
                if (!lastSearch.trim().isEmpty()) {
                    Boolean stitle = data.getBoolean("__LAST_TITLE__", true);
                    Boolean sauthor = data.getBoolean("__LAST_AUTHOR__", true);
                    searchBooks(lastSearch, stitle, sauthor);
                    return;
                }
            case BookDb.STATUS_ANY:
                title = R.string.book_status_any;
                showRecent = true;
                showingSearch = false;
                break;
            case BookDb.STATUS_NONE:
                title = R.string.book_status_none;
                showingSearch = false;
                break;
            case BookDb.STATUS_STARTED:
                title = R.string.book_status_started;
                showRecent = true;
                showingSearch = false;
                break;
            case BookDb.STATUS_DONE:
                title = R.string.book_status_completed2;
                showingSearch = false;
                break;
            case BookDb.STATUS_LATER:
                title = R.string.book_status_later2;
                showingSearch = false;
                break;
        }
        BookListActivity.this.setTitle(title);

        SortOrder sortorder = getSortOrder();
        final List<Integer> books = db.getBookIds(sortorder, status);
        populateBooks(books,  showRecent);

        invalidateOptionsMenu();
    }


    private void searchBooks(String searchfor, boolean stitle, boolean sauthor) {
        showStatus = BookDb.STATUS_SEARCH;
        data.edit().putInt(LASTSHOW_STATUS_KEY, showStatus).apply();
        List<Integer> books = db.searchBooks(searchfor, stitle, sauthor);
        populateBooks(books, false);
        BookListActivity.this.setTitle(getString(R.string.search_res_title, searchfor, books.size()));
        showingSearch = true;
        invalidateOptionsMenu();
    }

    private void populateBooks(final List<Integer> books, boolean showRecent) {

        if (showRecent) {
            recentread = db.getMostRecentlyRead();
            if (recentread >= 0) {
                //viewAdder.displayBook(recentread);
                books.remove((Integer) recentread);
                books.add(0, (Integer)recentread);
            }
        }

        bookAdapter.setBooks(books);
    }


    private void updateViewTimes() {
        bookAdapter.notifyItemRangeChanged(0, bookAdapter.getItemCount());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options, menu);

        SortOrder sortorder = getSortOrder();

        switch (sortorder) {
            case Default:
                menu.findItem(R.id.menu_sort_default).setChecked(true);
                break;
            case Author:
                menu.findItem(R.id.menu_sort_author).setChecked(true);
                break;
            case Title:
                menu.findItem(R.id.menu_sort_title).setChecked(true);
                break;
            case Added:
                menu.findItem(R.id.menu_sort_added).setChecked(true);
                break;
        }

        switch (data.getInt(STARTWITH_KEY, STARTLASTREAD)) {
            case STARTALL:
                menu.findItem(R.id.menu_start_all_books).setChecked(true);
                break;
            case STARTOPEN:
                menu.findItem(R.id.menu_start_open_books).setChecked(true);
                break;
            case STARTLASTREAD:
                menu.findItem(R.id.menu_start_last_read).setChecked(true);
                break;
        }

        return true;
    }


    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        //Log.d("Booky", "onPrepareOptionsMenu called, showingSearch=" + showingSearch);
        super.onPrepareOptionsMenu(menu);

        menu.findItem(R.id.menu_add).setVisible(!showingSearch);
        menu.findItem(R.id.menu_add_dir).setVisible(!showingSearch);
        menu.findItem(R.id.menu_get_books).setVisible(!showingSearch);
        menu.findItem(R.id.menu_sort).setVisible(!showingSearch);




        switch (showStatus) {
            case BookDb.STATUS_ANY:
                menu.findItem(R.id.menu_all_books).setChecked(true);
                break;
            case BookDb.STATUS_DONE:
                menu.findItem(R.id.menu_completed_books).setChecked(true);
                break;
            case BookDb.STATUS_LATER:
                menu.findItem(R.id.menu_later_books).setChecked(true);
                break;
            case BookDb.STATUS_NONE:
                menu.findItem(R.id.menu_unopen_books).setChecked(true);
                break;
            case BookDb.STATUS_STARTED:
                menu.findItem(R.id.menu_open_books).setChecked(true);
                break;
        }




        return true;
    }



    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int status = BookDb.STATUS_ANY;
        boolean pop = false;
        switch (item.getItemId()) {
            case R.id.menu_add:
                //case R.id.menu_add2:
                findFile();
                break;
            case R.id.menu_add_dir:
                findDir();
                break;
            case R.id.menu_about:
                showMsg(BookListActivity.this,getString(R.string.about), getString(R.string.about_app));
                break;
            case R.id.menu_sort_default:
                item.setChecked(true);
                setSortOrder(SortOrder.Default);
                pop = true;
                break;
            case R.id.menu_sort_author:
                item.setChecked(true);
                setSortOrder(SortOrder.Author);
                pop = true;
                break;
            case R.id.menu_sort_title:
                item.setChecked(true);
                setSortOrder(SortOrder.Title);
                pop = true;
                break;
            case R.id.menu_sort_added:
                item.setChecked(true);
                setSortOrder(SortOrder.Added);
                pop = true;
                break;
            case R.id.menu_get_books:
                Intent intent = new Intent(this, GetBooksActivity.class);
                startActivity(intent);
                break;
            case R.id.menu_completed_books:
                pop = true;
                status = BookDb.STATUS_DONE;
                break;
            case R.id.menu_later_books:
                pop = true;
                status = BookDb.STATUS_LATER;
                break;
            case R.id.menu_open_books:
                pop = true;
                status = BookDb.STATUS_STARTED;
                break;
            case R.id.menu_unopen_books:
                pop = true;
                status = BookDb.STATUS_NONE;
                break;
            case R.id.menu_search_books:
                showSearch();
                break;
            case R.id.menu_all_books:
                pop = true;
                status = BookDb.STATUS_ANY;
                break;
            case R.id.menu_start_all_books:
                data.edit().putInt(STARTWITH_KEY, STARTALL).apply(); break;
            case R.id.menu_start_open_books:
                data.edit().putInt(STARTWITH_KEY, STARTOPEN).apply(); break;
            case R.id.menu_start_last_read:
                data.edit().putInt(STARTWITH_KEY, STARTLASTREAD).apply(); break;
            default:

                return super.onOptionsItemSelected(item);
        }


        final int statusf = status;
        if (pop) {
            viewAdder.postDelayed(new Runnable() {
                @Override
                public void run() {
                    populateBooks(statusf);
                    invalidateOptionsMenu();
                }
            }, 120);
        }

        invalidateOptionsMenu();
        return true;
    }


    public static String maxlen(String text, int maxlen) {
        if (text!=null && text.length() > maxlen) {
            int minus = text.length()>3?3:0;

            return text.substring(0, maxlen-minus) + "...";
        }
        return text;
    }

    private void readBook(final int bookid) {

        final BookDb.BookRecord book = db.getBookRecord(bookid);

        if (book!=null && book.filename!=null) {
            //data.edit().putString(LASTREAD_KEY, BOOK_PREFIX + book.id).apply();

            final long now = System.currentTimeMillis();
            db.updateLastRead(bookid, now);
            recentread = bookid;

            viewAdder.postDelayed(new Runnable() {
                @Override
                public void run() {

                    getReader(book,true);

                }
            }, 300);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {

                try {

                    ShortcutManager shortcutManager = (ShortcutManager) getSystemService(Context.SHORTCUT_SERVICE);
                    if (shortcutManager!=null) {
                        Intent readBook = getReader(book,false);


                        ShortcutInfo readShortcut = new ShortcutInfo.Builder(this, "id1")
                                .setShortLabel(getString(R.string.shortcut_latest))
                                .setLongLabel(getString(R.string.shortcut_latest_title, maxlen(book.title, 24)))
                                .setIcon(Icon.createWithResource(BookListActivity.this, R.mipmap.ic_launcher_round))
                                .setIntent(readBook)
                                .build();



                        shortcutManager.setDynamicShortcuts(Collections.singletonList(readShortcut));
                    }
                } catch(Exception e) {
                    Log.e("Booky", e.getMessage(), e);
                }
            }


        }
    }

    private Intent getReader(BookDb.BookRecord book, boolean start) {
        Intent readBook = new Intent(BookListActivity.this, ReaderActivity.class);
        readBook.putExtra(ReaderActivity.FILENAME, book.filename);
        readBook.setAction(Intent.ACTION_VIEW);
        if (start) {
            bookAdapter.notifyItemIdChanged(book.id);
            startActivity(readBook);
        }
        return readBook;
    }


    private void removeBook(int bookid, boolean delete) {
        BookDb.BookRecord book = db.getBookRecord(bookid);
        if (book==null) {
            Toast.makeText(this, "Bug? The book doesn't seem to be in the database",Toast.LENGTH_LONG).show();
            return;
        }
        if (book.filename!=null && book.filename.length()>0) {
            Book.remove(this, new File(book.filename));
        }
        if (delete) {
            db.removeBook(bookid);
            if (bookAdapter!=null) bookAdapter.notifyItemIdRemoved(bookid);
        }
//        else if (status!=BookDb.STATUS_ANY) {
//            //db.updateLastRead(bookid, -1);
//            db.updateStatus(bookid, status);
//        }
        recentread = db.getMostRecentlyRead();
    }

    private boolean addBook(String filename) {
        return addBook(filename, true, System.currentTimeMillis());
    }

    private boolean addBook(String filename, boolean showToastWarnings, long dateadded) {

        try {
            if (db.containsBook(filename)) {

                if (showToastWarnings) {
                    Toast.makeText(this, getString(R.string.already_added, new File(filename).getName()), Toast.LENGTH_SHORT).show();
                }
                return false;
            }

            BookMetadata metadata = Book.getBookMetaData(this, filename);

            if (metadata!=null) {

                return db.addBook(filename, metadata.getTitle(), metadata.getAuthor(), dateadded) > -1;

            } else if (showToastWarnings) {
                Toast.makeText(this,getString(R.string.coulndt_add_book, new File(filename).getName()),Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e("BookList", "File: " + filename  + ", " + e.getMessage(), e);
        }
        return false;
    }

    private void findFile() {

        FsTools fsTools = new FsTools(this);

        if (checkStorageAccess(false)) {
            fsTools.selectExternalLocation(new FsTools.SelectionMadeListener() {
                @Override
                public void selected(File selection) {
                    addBook(selection.getPath());
                    populateBooks();

                }
            }, getString(R.string.find_book), false, Book.getFileExtensionRX());
        }
    }

    private void showProgress(int added) {

        if (tv.getVisibility() != View.VISIBLE) {
            tv.setVisibility(View.VISIBLE);
            tv.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return true;
                }
            });
        }
        if (added>0) {
            tv.setText(getString(R.string.added_numbooks, added));
        } else {
            tv.setText(R.string.loading);
        }
    }

    private void hideProgress() {
        tv.setVisibility(View.GONE);
    }


    private void addDir( File dir) {

        viewAdder.showProgress(0);
        new AddDirTask(this, dir).execute(dir);
    }

    private static class AddDirTask extends  AsyncTask<File,Void,Void> {

        int added=0;
        private final WeakReference<BookListActivity> blactref;
        private final File dir;


        AddDirTask(BookListActivity blact,  File dir) {
            blactref = new WeakReference<>(blact);
            this.dir = dir;
        }

        @Override
        protected Void doInBackground(File... dirs) {
            BookListActivity blact = blactref.get();
            if (blact!=null && dirs!=null) {
                long time = System.currentTimeMillis();
                for (File d : dirs) {
                    try {
                        if (d == null || !d.isDirectory()) continue;
                        for (final File file : d.listFiles()) {
                            try {
                                if (file == null) continue;
                                if (file.isFile() && file.getName().matches(Book.getFileExtensionRX())) {
                                    if (blact.addBook(file.getPath(), false, time)) {
                                        added++;
                                    }
                                    blact.viewAdder.showProgress(added);

                                } else if (file.isDirectory()) {
                                    doInBackground(file);
                                }
                            } catch (Exception e) {
                                Log.e("Booky", e.getMessage(), e);
                            }
                        }
                    } catch (Exception e) {
                        Log.e("Booky", e.getMessage(), e);
                    }
                }
            }
            return null;
        }


        @Override
        protected void onPostExecute(Void aVoid) {
            BookListActivity blact = blactref.get();
            if (blact!=null) {
                blact.viewAdder.hideProgress();
                Toast.makeText(blact, blact.getString(R.string.books_added, added), Toast.LENGTH_LONG).show();
                blact.populateBooks();
            }
        }

        @Override
        protected void onCancelled(Void aVoid) {
            BookListActivity blact = blactref.get();
            if (blact!=null) {
                blact.viewAdder.hideProgress();
            }
            super.onCancelled(aVoid);
        }
    }

    private void findDir() {

        FsTools fsTools = new FsTools(this);

        if (checkStorageAccess(false)) {
            fsTools.selectExternalLocation(new FsTools.SelectionMadeListener() {
                @Override
                public void selected(File selection) {
                    addDir(selection);
                }
            }, getString(R.string.find_folder), true);
        }
    }


    private void longClickBook(final View view) {
        final int bookid = (int)view.getTag();
        PopupMenu menu = new PopupMenu(this, view);
        menu.getMenu().add(R.string.open_book).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                readBook(bookid);
                return false;
            }
        });

        final int status = db.getStatus(bookid);
        final long lastread = db.getLastReadTime(bookid);

        if (status!=BookDb.STATUS_DONE) {
            menu.getMenu().add(R.string.mark_completed).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {
                    if (lastread > 0) {
                        removeBook(bookid, false);
                    } else {
                        db.updateLastRead(bookid, System.currentTimeMillis());
                    }
                    updateBookStatus(bookid, view, BookDb.STATUS_DONE);

                    return false;
                }
            });
        }

        if (status!=BookDb.STATUS_LATER && status!=BookDb.STATUS_DONE) {
            menu.getMenu().add(R.string.mark_later).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {
                    updateBookStatus(bookid, view, BookDb.STATUS_LATER);
                    return false;
                }
            });
        }

        if (status==BookDb.STATUS_LATER || status==BookDb.STATUS_DONE) {
            menu.getMenu().add(R.string.un_mark).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {

                    updateBookStatus(bookid, view, lastread>0 ? BookDb.STATUS_STARTED : BookDb.STATUS_NONE);
                    return false;
                }
            });
        }


        if (status==BookDb.STATUS_STARTED) {

            menu.getMenu().add(R.string.close_book).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {
                    removeBook(bookid, false);
                    updateBookStatus(bookid, view, BookDb.STATUS_NONE);
                    //updateViewTimes();
                    return false;
                }
            });
        }


        menu.getMenu().add(R.string.remove_book).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                //((ViewGroup)view.getParent()).removeView(view);
                removeBook(bookid, true);
                return false;
            }
        });
        menu.show();
    }

    private void updateBookStatus(int bookid, View view, int status) {
        db.updateStatus(bookid, status);
        if (bookAdapter!=null) bookAdapter.notifyItemIdChanged(bookid);
//        listHolder.removeView(view);
//        listHolder.addView(view);
        //       updateViewTimes();
    }

    private boolean checkStorageAccess(boolean yay) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    yay? REQUEST_READ_EXTERNAL_STORAGE : REQUEST_READ_EXTERNAL_STORAGE_NOYAY);
            return false;
        }
        return true;
    }

    private static final int REQUEST_READ_EXTERNAL_STORAGE_NOYAY = 4333;
    private static final int REQUEST_READ_EXTERNAL_STORAGE = 4334;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        boolean yay = true;
        switch (requestCode) {
            case REQUEST_READ_EXTERNAL_STORAGE_NOYAY:
                yay = false;
            case REQUEST_READ_EXTERNAL_STORAGE:
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    if (yay) Toast.makeText(this, "Yay", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Boo", Toast.LENGTH_LONG).show();
                }

        }
    }

    private static void showMsg(Context context, String title, String message) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);

        final TextView messageview = new TextView(context);
        messageview.setPadding(32,8,32,8);

        final SpannableString s = new SpannableString(message);
        Linkify.addLinks(s, Linkify.ALL);
        messageview.setText(s);
        messageview.setMovementMethod(LinkMovementMethod.getInstance());
        messageview.setTextSize(18);

        builder.setView(messageview);

        builder.setNeutralButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void showSearch() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle(android.R.string.search_go);

        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.search, null);
        builder.setView(dialogView);

        final EditText editText =  dialogView.findViewById(R.id.search_text);
        final RadioButton author = dialogView.findViewById(R.id.search_author);
        final RadioButton title = dialogView.findViewById(R.id.search_title);
        final RadioButton authortitle = dialogView.findViewById(R.id.search_authortitle);

        builder.setPositiveButton(android.R.string.search_go, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                String searchfor = editText.getText().toString();

                if (!searchfor.trim().isEmpty()) {
                    boolean stitle = title.isChecked() || authortitle.isChecked();
                    boolean sauthor = author.isChecked() || authortitle.isChecked();
                    data.edit()
                            .putString("__LAST_SEARCH_STR__", searchfor)
                            .putBoolean("__LAST_TITLE__", stitle)
                            .putBoolean("__LAST_AUTHOR__", sauthor)
                            .apply();

                    searchBooks(searchfor, stitle, sauthor);
                } else {
                    dialogInterface.cancel();
                }
            }
        });

        builder.setNegativeButton(android.R.string.cancel, null);

        editText.setFocusable(true);
        final AlertDialog alertDialog = builder.create();
        alertDialog.show();

        title.setChecked(data.getBoolean("__LAST_TITLE__", false));
        author.setChecked(data.getBoolean("__LAST_AUTHOR__", false));

        String lastSearch = data.getString("__LAST_SEARCH_STR__","");
        editText.setText(lastSearch);
        editText.setSelection(lastSearch.length());
        editText.setSelection(0, lastSearch.length());

        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!lastSearch.isEmpty());

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                alertDialog.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setEnabled(!editText.getText().toString().trim().isEmpty());
            }
        });


        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        editText.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        editText.setImeActionLabel(getString(android.R.string.search_go), EditorInfo.IME_ACTION_SEARCH);

        editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                } else if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || event == null
                        || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    if (!editText.getText().toString().trim().isEmpty()) {
                        editText.clearFocus();

                        if (imm != null) imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
                        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick();
                    }
                    return true;
                }

                return false;
            }
        });

        editText.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (imm!=null) imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 100);

    }

    private static class BookListAdderHandler extends Handler {

        private static final int SHOW_PROGRESS = 1002;
        private static final int HIDE_PROGRESS = 1003;
        private final WeakReference<BookListActivity> weakReference;

        BookListAdderHandler(BookListActivity blInstance) {
            weakReference = new WeakReference<>(blInstance);
        }


        void showProgress(int progress) {
            Message msg=new Message();
            msg.arg1 = BookListAdderHandler.SHOW_PROGRESS;
            msg.arg2 = progress;
            sendMessage(msg);
        }
        void hideProgress() {
            Message msg=new Message();
            msg.arg1 = BookListAdderHandler.HIDE_PROGRESS;
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            BookListActivity blInstance = weakReference.get();
            if (blInstance != null) {
                switch (msg.arg1) {

                    case SHOW_PROGRESS:
                        blInstance.showProgress(msg.arg2);
                        break;
                    case HIDE_PROGRESS:
                        blInstance.hideProgress();
                        break;
                }
            }
        }
    }

}



package com.quaap.bookymcbookface;

        import android.app.Application;
        import android.content.Context;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class BookyApp extends Application {

    private BookDb db;

    @Override
    public void onCreate() {
        super.onCreate();

        db = new BookDb(this);
    }

    public static BookDb getDB(Context context) {
        return ((BookyApp)context.getApplicationContext()).db;
    }


    @Override
    public void onTerminate() {
        if (db!=null) db.close();

        super.onTerminate();
    }
}




package com.quaap.bookymcbookface;


        import android.app.Activity;
        import android.content.Context;
        import android.content.Intent;
        import android.graphics.Color;
        import android.net.Uri;
        import android.os.Bundle;
        import android.util.Log;
        import android.view.MenuItem;
        import android.view.View;
        import android.view.inputmethod.InputMethodManager;
        import android.widget.Button;
        import android.widget.EditText;
        import android.widget.LinearLayout;
        import android.widget.PopupMenu;
        import android.widget.TextView;
        import android.widget.Toast;

        import java.util.Map;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
public class GetBooksActivity extends Activity implements View.OnClickListener, View.OnLongClickListener{

    private EditText nameBox;
    private EditText urlBox;

    private LinearLayout list;

    private BookDb db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_get_books);

        db = BookyApp.getDB(this);

        list = findViewById(R.id.webs_list);

        nameBox = findViewById(R.id.web_name);
        urlBox = findViewById(R.id.web_url);
        Button wadd = findViewById(R.id.web_add);

        final Button wnew = findViewById(R.id.web_new);
        final LinearLayout add_layout= findViewById(R.id.web_add_layout);


        wadd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (urlBox.getText().length()>0) {
                    String url = urlBox.getText().toString();
                    String name = url.replaceAll("^https?://([\\w\\-.])(/.*)","$1");
                    if (nameBox.getText().length()>0) {
                        name = nameBox.getText().toString();
                    }
                    db.addWebsite(name, url);
                    displayWeb(name, url, true);
                    InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm!=null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    add_layout.setVisibility(View.GONE);
                    wnew.setVisibility(View.VISIBLE);
                }
            }
        });

        wnew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                add_layout.setVisibility(View.VISIBLE);
                wnew.setVisibility(View.GONE);
            }
        });



        Map<String,String> webs = db.getWebSites();

        for (Map.Entry<String,String> web: webs.entrySet()) {
            displayWeb(web.getValue(), web.getKey());
        }

    }

    private void displayWeb(String name, String url) {
        displayWeb(name, url, false);
    }

    private void displayWeb(String name, String url, boolean first) {
        TextView v = new TextView(this);
        v.setTextSize(24);
        v.setTextColor(Color.BLUE);
        v.setPadding(16,16,8,8);
        v.setText(name);
        v.setTag(url);
        v.setOnClickListener(this);
        v.setOnLongClickListener(this);
        if (first) {
            list.addView(v, 0);
        } else {
            list.addView(v);
        }

    }

    @Override
    public void onClick(View v) {
        try {
            String url = (String) v.getTag();
            if (url != null) {
                Uri uri = Uri.parse(url);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e("Webs", e.getMessage(), e);
        }
    }

    @Override
    public boolean onLongClick(final View v) {

        PopupMenu p = new PopupMenu(this, v);
        MenuItem m = p.getMenu().add("Delete");

        m.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                list.removeView(v);
                db.deleteWebSite((String)v.getTag());
                return true;
            }
        });
        p.show();
        return true;
    }
}




package com.quaap.bookymcbookface;

        import android.annotation.SuppressLint;
        import android.app.ActionBar;
        import android.app.Activity;
        import android.content.Context;
        import android.content.Intent;
        import android.graphics.Color;
        import android.graphics.Point;
        import android.graphics.PorterDuff;
        import android.graphics.drawable.ColorDrawable;
        import android.graphics.drawable.Drawable;
        import android.hardware.Sensor;
        import android.hardware.SensorEvent;
        import android.hardware.SensorEventListener;
        import android.hardware.SensorManager;
        import android.net.Uri;
        import android.os.AsyncTask;
        import android.os.Build;
        import android.os.Bundle;
        import android.os.Handler;
        import android.support.annotation.RequiresApi;
        import android.util.Log;
        import android.view.Display;
        import android.view.MenuItem;
        import android.view.MotionEvent;
        import android.view.View;
        import android.view.ViewGroup;
        import android.view.Window;
        import android.view.WindowManager;
        import android.webkit.WebResourceRequest;
        import android.webkit.WebView;
        import android.webkit.WebViewClient;
        import android.widget.CheckBox;
        import android.widget.CompoundButton;
        import android.widget.ImageButton;
        import android.widget.PopupMenu;
        import android.widget.ProgressBar;
        import android.widget.Toast;

        import java.io.File;
        import java.lang.ref.WeakReference;
        import java.util.Map;
        import java.util.Timer;
        import java.util.TimerTask;
        import java.util.concurrent.atomic.AtomicInteger;

        import com.quaap.bookymcbookface.book.Book;

/**
 * Copyright (C) 2017   Tom Kliethermes
 *
 * This file is part of BookyMcBookface and is is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

public class ReaderActivity extends Activity {

    private static final String TAG = "ReaderActivity";
    public static final String READEREXITEDNORMALLY = "readerexitednormally";
    private static final String FULLSCREEN = "fullscreen";

    private Book book;

    private WebView webView;

    public static final String FILENAME = "filename";


    private final Object timerSync = new Object();
    private Timer timer;

    private TimerTask nowakeTask = null;
    private TimerTask scrollTask = null;

    private volatile int scrollDir;

    private final Handler handler = new Handler();

    private CheckBox fullscreenBox;

    private ProgressBar progressBar;

    private Point mScreenDim;

    private Throwable exception;

    private int currentDimColor = Color.TRANSPARENT;

    private boolean hasLightSensor = false;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        ActionBar ab = getActionBar();
        if (ab!=null) ab.hide();
        Display display = getWindowManager().getDefaultDisplay();
        mScreenDim = new Point();
        display.getSize(mScreenDim);

        SensorManager sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor != null) {
            hasLightSensor = true;
        }

        final ImageButton showMore = findViewById(R.id.control_view_more);

        webView = findViewById(R.id.page_view);

        webView.getSettings().setDefaultFontSize(18);
        webView.getSettings().setDefaultFixedFontSize(18);

        webView.setNetworkAvailable(false);
        //webView.setScrollContainer(false);
        webView.setOnTouchListener(new View.OnTouchListener() {
            float x,y;
            long time;
            final long TIMEALLOWED = 300;
            final int MINSWIPE = 150;
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                float diffx = 0;
                float diffy = 0;
                switch (motionEvent.getAction()) {

                    case MotionEvent.ACTION_UP:

                        cancelScrollTask();
                        //Log.d("TIME", "t " + (System.currentTimeMillis() - time));
                        if (System.currentTimeMillis() - time >TIMEALLOWED) return false;

                        diffx = motionEvent.getX() - x;
                        diffy = motionEvent.getY() - y;
                        float absdiffx = Math.abs(diffx);
                        float absdiffy = Math.abs(diffy);


                        if ((absdiffx>absdiffy && diffx>MINSWIPE) || (absdiffy>absdiffx && diffy>MINSWIPE)) {
                            prevPage();
                        } else if ((absdiffx>absdiffy && diffx<-MINSWIPE) || (absdiffy>absdiffx && diffy<-MINSWIPE)) {
                            nextPage();
                        } else {
                            return false;
                        }


                    case MotionEvent.ACTION_DOWN:
                        cancelScrollTask();
                        x = motionEvent.getX();
                        y = motionEvent.getY();
                        time = System.currentTimeMillis();
                        setAwake();
                        if (y>mScreenDim.y/3 && x>mScreenDim.x/3 &&
                                y<mScreenDim.y*2/3 && x<mScreenDim.x*2/3) {
                            mkFull();
                            hideMenu();

                            if (currentDimColor!=Color.TRANSPARENT) {
                                setDimLevel(showMore, Color.LTGRAY);
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        setDimLevel(showMore, currentDimColor);
                                    }
                                }, 2000);
                            }
                        }
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        diffy = motionEvent.getY() - y;
                        if (Math.abs(diffy) > 30) {
                            if (System.currentTimeMillis() - time > TIMEALLOWED*1.5) {
                                scrollDir = (int) ((-diffy/webView.getHeight())*webView.getSettings().getDefaultFontSize()*5);
                                startScrollTask();
                                webView.clearMatches();
                            }
                        } else {
                            cancelScrollTask();
                        }
                        return true;

                }


                return true;
            }



        });


        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.i(TAG, "Attempting to load URL: " + url);

                handleLink(url);
                return true;
            }


            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri.getScheme().equals("file")) {
                    handleLink(uri.toString());
                    return true;
                }
                return false;
            }


            public void onPageFinished(WebView view, String url) {
                // addEOCPadding();
                try {
                    restoreBgColor();
                    restoreScrollOffsetDelayed(100);
                } catch (Throwable t) {
                    Log.e(TAG, t.getMessage(), t);
                }
            }

        });


        progressBar = findViewById(R.id.progressBar);

        findViewById(R.id.prev_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prevPage();
            }
        });

        findViewById(R.id.next_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                nextPage();
            }
        });

        findViewById(R.id.contents_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showToc();
                //hideMenu();
            }
        });

        findViewById(R.id.zoom_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectFontSize();
                //hideMenu();
            }
        });
        findViewById(R.id.brightness_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showBrightnessControl();
                //hideMenu();
            }
        });

        showMore.setOnClickListener(morelessControls);
        findViewById(R.id.control_view_less).setOnClickListener(morelessControls);

        fullscreenBox = findViewById(R.id.fullscreen_box);

        fullscreenBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                setFullscreen(b);
                if (b) {
                    fullscreenBox.postDelayed(
                            new Runnable() {
                                @Override
                                public void run() {
                                    mkFull();
                                    hideMenu();
                                }
                            }, 500);
                } else {
                    fullscreenBox.postDelayed(
                            new Runnable() {
                                @Override
                                public void run() {
                                    mkReg();
                                    hideMenu();
                                }
                            }, 500);
                }
            }
        });

        findViewById(R.id.fullscreen_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fullscreenBox.setChecked(!fullscreenBox.isChecked());
            }
        });

        //findFile();
        Intent intent = getIntent();
        String filename = intent.getStringExtra(FILENAME);
        if (filename!=null) {
            //if the app crashes on this book,
            // this flag will remain to let the booklist activity know not to auto start it again.
            // it gets set to true in onPause.
            if (getSharedPreferences(BookListActivity.prefname, Context.MODE_PRIVATE).edit().putBoolean(READEREXITEDNORMALLY, false).commit()) {
                loadFile(new File(filename));
            }
        }

    }

    @Override
    public void onBackPressed() {
        finish();
        Intent main = new Intent(this, BookListActivity.class);
        main.setAction(BookListActivity.ACTION_SHOW_LAST_STATUS);
        startActivity(main);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void addEOCPadding() {
        //Add padding to end of section to reduce confusing partial page scrolls
        webView.getSettings().setJavaScriptEnabled(true);
        webView.evaluateJavascript("document.getElementsByTagName('body')[0].innerHTML += '<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>'", null);
        webView.getSettings().setJavaScriptEnabled(false);
    }

    private final View.OnClickListener morelessControls = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            View v = findViewById(R.id.slide_menu);
            if (v.getVisibility()==View.GONE) {
                showMenu();
            } else {
                hideMenu();
            }
        }
    };
    private void setFullscreenMode() {
        if (book!=null && book.hasDataDir()) {
            setFullscreen(book.getFlag(FULLSCREEN, true));
        }
    }

    private void setFullscreen(boolean full) {
        if (book!=null && book.hasDataDir()) book.setFlag(FULLSCREEN, full);

        fullscreenBox.setChecked(full);
    }

    private void showMenu() {
        View v = findViewById(R.id.slide_menu);
        v.setVisibility(View.VISIBLE);
        findViewById(R.id.control_view_more).setVisibility(View.GONE);
        findViewById(R.id.control_view_less).setVisibility(View.VISIBLE);
        mkReg();
    }

    private void hideMenu() {
        View v = findViewById(R.id.slide_menu);
        v.setVisibility(View.GONE);
        findViewById(R.id.control_view_more).setVisibility(View.VISIBLE);
        findViewById(R.id.control_view_less).setVisibility(View.GONE);
        mkFull();
    }

    private void startScrollTask() {
        synchronized (timerSync) {
            if (scrollTask == null) {
                scrollTask = new TimerTask() {
                    @Override
                    public void run() {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                webView.scrollBy(0, scrollDir);
                            }
                        });
                    }
                };
                try {
                    timer.schedule(scrollTask, 0, 100);
                } catch(IllegalStateException e) {
                    Log.d(TAG, e.getMessage(), e);
                    Toast.makeText(this,"Something went wrong. Please report a 'scroll' bug.",Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void cancelScrollTask() {
        if (scrollTask!=null) {
            scrollTask.cancel();
            scrollTask = null;
        }
    }

    private boolean isPagingDown;
    private boolean isPagingUp;

    private void prevPage() {
        isPagingDown = false;
        if(webView.canScrollVertically(-1)) {
            webView.pageUp(false);
            //webView.scrollBy(0,-webView.getHeight()-14);
        } else {
            isPagingUp = true;
            showUri(book.getPreviousSection());
        }
        //saveScrollOffsetDelayed(1500);
        hideMenu();

    }

    private void nextPage() {
        isPagingUp = false;
        if(webView.canScrollVertically(1)) {
            webView.pageDown(false);
            //webView.scrollBy(0,webView.getHeight()-14);
        } else {
            isPagingDown = true;
            if (book!=null) showUri(book.getNextSection());


        }
        //saveScrollOffsetDelayed(1500);
        hideMenu();
    }

    private void saveScrollOffsetDelayed(int delay) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                saveScrollOffset();
            }
        }, delay);
    }

    private void saveScrollOffset() {
        webView.computeScroll();
        saveScrollOffset(webView.getScrollY());
    }

    private void saveScrollOffset(int offset) {
        if (book==null) return;
        book.setSectionOffset(offset);
    }

    private void restoreScrollOffsetDelayed(int delay) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                restoreScrollOffset();
            }
        }, delay);
    }

    private void restoreScrollOffset() {
        if (book==null) return;
        int spos = book.getSectionOffset();
        webView.computeScroll();
        if (spos>=0) {
            webView.scrollTo(0, spos);
            Log.d(TAG, "restoreScrollOffset " + spos);
        } else if (isPagingUp){
            webView.pageDown(true);
            //webView.scrollTo(0,webView.getContentHeight());
        } else if (isPagingDown){
            webView.pageUp(true);
        }
        isPagingUp = false;
        isPagingDown = false;
    }

    private void loadFile(File file) {

        webView.loadData("Loading " + file.getPath(),"text/plain", "utf-8");

        new LoaderTask(this, file).execute();

    }


    private static class LoaderTask extends  AsyncTask<Void,Integer,Book>  {

        private final File file;
        private final WeakReference<ReaderActivity> ractref;

        LoaderTask(ReaderActivity ract, File file) {
            this.file = file;
            this.ractref = new WeakReference<>(ract);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            ReaderActivity ract = ractref.get();
            if (ract!=null) {
                ract.progressBar.setProgress(0);
                ract.progressBar.setVisibility(View.VISIBLE);

            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
            ReaderActivity ract = ractref.get();
            if (ract!=null) {
                ract.progressBar.setProgress(values[0]);
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            ReaderActivity ract = ractref.get();
            if (ract!=null) {
                ract.progressBar.setVisibility(View.GONE);
            }
        }

        @Override
        protected Book doInBackground(Void... voids) {
            ReaderActivity ract = ractref.get();
            if (ract==null) return null;
            try {
                ract.book = Book.getBookHandler(ract, file.getPath());
                Log.d(TAG, "File " + file);
                if (ract.book!=null) {
                    ract.book.load(file);
                    return ract.book;
                }

                //publishProgress(1);

            } catch (Throwable e) {
                ract.exception = e;
                Log.e(TAG, e.getMessage(), e);
            }
            return null;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        protected void onPostExecute(Book book) {

            ReaderActivity ract = ractref.get();
            if (ract==null) return;

            String badtext = ract.getString(R.string.book_bug);
            try {
                ract.progressBar.setVisibility(View.GONE);

                if (book==null && ract.exception!=null) {
                    ract.webView.setOnTouchListener(null);
                    ract.webView.setWebViewClient(null);
                    ract.webView.loadData(badtext + ract.exception.getLocalizedMessage(),"text/plain", "utf-8");
                    throw ract.exception;
                }
                if (book !=null && ract.book != null && ract.book.hasDataDir()) {
                    int fontsize = ract.book.getFontsize();
                    if (fontsize != -1) {
                        ract.setFontSize(fontsize);
                    }
                    Uri uri = ract.book.getCurrentSection();
                    if (uri != null) {
                        ract.showUri(uri);
                    } else {
                        Toast.makeText(ract, badtext + " (no sections)", Toast.LENGTH_LONG).show();
                    }
                    if (ract.book.getFlag(FULLSCREEN, true)) {
                        ract.mkFull();
                    } else {
                        ract.mkReg();
                    }
                    ract.setFullscreenMode();
                    ract.setAwake();
                }
            } catch (Throwable e) {
                Log.e(TAG, e.getMessage(), e);
                Toast.makeText(ract, badtext + e.getMessage(), Toast.LENGTH_LONG).show();
            }

        }
    }


    private void showUri(Uri uri) {
        if (uri !=null) {
            Log.d(TAG, "trying to load " + uri);

            //book.clearSectionOffset();
            webView.loadUrl(uri.toString());
        }
    }

    private void handleLink(String clickedLink) {
        if (clickedLink!=null) {
            Log.d(TAG, "clicked on " + clickedLink);
            showUri(book.handleClickedLink(clickedLink));
        }

    }


    private void fontSizeToggle() {

        int defsize = webView.getSettings().getDefaultFontSize();
        int minsize = webView.getSettings().getMinimumFontSize();

        defsize += 4;
        if (defsize>40) {
            defsize = minsize;
        }

        setFontSize(defsize);

    }

    private void setFontSize(int size) {
        book.setFontsize(size);
        webView.getSettings().setDefaultFontSize(size);
        webView.getSettings().setDefaultFixedFontSize(size);
    }

    private void selectFontSize() {
        final int defsize = webView.getSettings().getDefaultFontSize();
        int minsize = webView.getSettings().getMinimumFontSize();
        final float scale = getResources().getDisplayMetrics().density;


        // Log.d(TAG, "def " + defsize + " " + scale);
        final PopupMenu sizemenu = new PopupMenu(this, findViewById(R.id.zoom_button));
        for (int size=minsize; size<=36; size+=2) {
            final int s = size;

            MenuItem mi = sizemenu.getMenu().add(" "+size);
            mi.setCheckable(true);
            mi.setChecked(size==defsize);

            mi.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {
                    Log.d(TAG, "def " + (defsize-s));
                    int scrolloffset = (int)(-webView.getScrollY()*(defsize - s)/scale/2.7);
                    Log.d(TAG, "scrollby " + scrolloffset);

                    setFontSize(s);

                    //attempt to adjust the scroll to keep the same text position.
                    //  needs much work
                    webView.scrollBy(0, scrolloffset);
                    sizemenu.dismiss();
                    return true;
                }
            });
        }
        sizemenu.show();


    }

    private void mkFull() {

        if (book==null || !book.getFlag(FULLSCREEN, true)) return;
//        findViewById(R.id.fullscreen_no_button).setVisibility(View.VISIBLE);
//        findViewById(R.id.fullscreen_button).setVisibility(View.GONE);

        View decorView = getWindow().getDecorView();
        // Hide the status bar.
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE;
        decorView.setSystemUiVisibility(uiOptions);
    }

    private void mkReg() {

//        findViewById(R.id.fullscreen_button).setVisibility(View.VISIBLE);
//        findViewById(R.id.fullscreen_no_button).setVisibility(View.GONE);

        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decorView.setSystemUiVisibility(uiOptions);
    }

    @Override
    protected void onResume() {
        super.onResume();

        synchronized (timerSync) {
            if (timer != null) {
                timer.cancel();
            }
            timer = new Timer();
        }
        restoreBgColor();
    }

    @Override
    protected void onPause() {
        setNoAwake();
        unlistenLight();
        synchronized (timerSync) {
            if (timer != null) {
                timer.cancel();
                timer.purge();
                timer = null;
            }
        }

        if (exception==null) {
            try {
                saveScrollOffset();
            } catch (Throwable t) {
                Log.e(TAG, t.getMessage(), t);
            }
            getSharedPreferences(BookListActivity.prefname, Context.MODE_PRIVATE).edit().putBoolean(READEREXITEDNORMALLY, true).apply();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (timer!=null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
        super.onDestroy();
    }

    //    @Override
//    public void onWindowFocusChanged(boolean hasFocus) {
//        super.onWindowFocusChanged(hasFocus);
//        //if (hasFocus) mkFull();
//    }

    private void showToc() {
        Map<String,String> tocmap = book.getToc();
        PopupMenu tocmenu = new PopupMenu(this, findViewById(R.id.contents_button));
        for (final String point: tocmap.keySet()) {
            String text = tocmap.get(point);
            MenuItem m = tocmenu.getMenu().add(text);
            //Log.d("EPUB", "TOC2: " + text + ". File: " + point);
            m.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {
                    handleLink(point);
                    return true;
                }
            });
        }
        if (tocmap.size()==0) {
            tocmenu.getMenu().add(R.string.no_toc_found);
        }

        tocmenu.show();

    }


    //keep the screen on for a few minutes, but not forever
    private void setAwake() {
        try {
            Window w = this.getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            synchronized (timerSync) {
                if (nowakeTask != null) {
                    nowakeTask.cancel();
                    if (timer==null)  {
                        timer = new Timer();
                        Log.d(TAG, "timer was null?");
                    }
                    timer.purge();
                }
                nowakeTask = new TimerTask() {
                    @Override
                    public void run() {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    setNoAwake();
                                    Log.d(TAG, "Clear FLAG_KEEP_SCREEN_ON");
                                } catch (Throwable t) {
                                    Log.e(TAG, t.getMessage(), t);
                                }

                            }
                        });
                    }
                };

                try {
                    if (timer==null)  return;
                    timer.schedule(nowakeTask, 3 * 60 * 1000);
                } catch (IllegalStateException e) {
                    Log.d(TAG, e.getMessage(), e);
                    //Toast.makeText(this, "Something went wrong. Please report a 'setAwake' bug.", Toast.LENGTH_LONG).show();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, t.getMessage(), t);
            setNoAwake();
        }

    }

    private void setNoAwake() {
        try {
            Window w = ReaderActivity.this.getWindow();
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }catch (Throwable t) {
            Log.e(TAG, t.getMessage(), t);
        }
    }

    private SensorEventListener lightSensorListener;


    private void listenLight() {

        unlistenLight();

        SensorManager sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor != null) {

            lightSensorListener = new SensorEventListener() {

                private final AtomicInteger currentLux = new AtomicInteger(0);
                private int lastCol = 0;

                private final int mincol = 30;
                private final int maxcol = 240;
                private final double luxThreshold = 50;
                private final double multfac = (maxcol-mincol)/luxThreshold;

                private Runnable changer;

                @Override
                public void onSensorChanged(SensorEvent event) {

                    try {
                        currentLux.set((int) event.values[0]);

                        if (changer == null) {
                            changer = new Runnable() {
                                @Override
                                public void run() {
                                    changer = null;
                                    try {
                                        float lux = currentLux.get();

                                        int col = maxcol;
                                        if (lux < luxThreshold) {

                                            col = (int) (lux * multfac + mincol);
                                            if (col < mincol) col = mincol;
                                            if (col > maxcol) col = maxcol;

                                        }
                                        Log.d(TAG, "lightval " + lux + " grey " + col);

                                        if (Math.abs(lastCol - col) > 1 * multfac) {

                                            lastCol = col;
                                            int color = Color.argb(255, col + 15, col + 10, (int) (col + Math.min(lux / luxThreshold * 10, 10)));

                                            applyColor(color);
                                        }
                                    } catch (Throwable t) {
                                        Log.e(TAG, t.getMessage(), t);
                                    }

                                }
                            };
                            handler.postDelayed(changer, 3000);


                        }
                    } catch (Throwable t) {
                        Log.e(TAG, t.getMessage(), t);
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {

                }
            };

            sensorManager.registerListener(
                    lightSensorListener,
                    lightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
        }

    }

    private void unlistenLight() {
        try {
            if (lightSensorListener != null) {
                SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
                sensorManager.unregisterListener(lightSensorListener);
                lightSensorListener = null;
            }
        }  catch (Throwable t) {
            Log.e(TAG, t.getMessage(), t);
        }
    }


    private void showBrightnessControl() {
        if (book==null) return;

        PopupMenu bmenu = new PopupMenu(this, findViewById(R.id.brightness_button));
        int bg = book.getBackgroundColor();

        MenuItem norm = bmenu.getMenu().add(R.string.book_default);

        if (bg==Integer.MAX_VALUE) {
            norm.setCheckable(true);
            norm.setChecked(true);
        }

        norm.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                unlistenLight();
                saveScrollOffset();
                book.clearBackgroundColor();
                resetColor();
                webView.reload();
                return true;
            }
        });


        if (hasLightSensor) {
            MenuItem auto = bmenu.getMenu().add(getString(R.string.auto_bright));

            if (bg == Color.TRANSPARENT) {
                auto.setCheckable(true);
                auto.setChecked(true);
            }

            auto.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    book.setBackgroundColor(Color.TRANSPARENT);
                    restoreBgColor();
                    return true;
                }
            });

        }


        for (int i = 0; i<7; i++) {
            int b = i*33;
            final int color = Color.argb(255, 255-b, 250-b, 250-i-b);
            String strcolor;
            switch (i) {
                case 0:
                    strcolor = (i+1) + " - " + getString(R.string.bright);
                    break;
                case 3:
                    strcolor = (i+1) + " - " + getString(R.string.bright_medium);
                    break;
                case 6:
                    strcolor = (i+1) + " - " + getString(R.string.dim);
                    break;
                default:
                    strcolor = (i+1) + "";

            }

            MenuItem m = bmenu.getMenu().add(strcolor);
            m.setIcon(new ColorDrawable(color));
            if (bg==color) {
                m.setCheckable(true);
                m.setChecked(true);
            }

            m.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    unlistenLight();
                    book.setBackgroundColor(color);
                    restoreBgColor();
                    return true;
                }
            });
        }
        bmenu.show();
    }

    private void restoreBgColor() {
        if (book!=null && book.hasDataDir()) {
            int bgcolor = book.getBackgroundColor();
            switch (bgcolor) {
                case Color.TRANSPARENT:
                    listenLight();
                    break;
                case Integer.MAX_VALUE:
                    unlistenLight();
                    resetColor();
                    //book.clearBackgroundColor();
                    //webView.reload();
                    break;
                default:
                    unlistenLight();
                    applyColor(bgcolor);
            }
        }
    }

    private void applyColor(int color) {
        applyColor(color, false);
    }

    private void resetColor() {
        applyColor(Color.argb(255,245,245,245), true);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applyColor(int color, boolean controlsonly) {
        currentDimColor = color;
        try {

            ViewGroup controls = findViewById(R.id.controls_layout);
            setDimLevel(controls, color);
            for (int i = 0; i < controls.getChildCount(); i++) {
                View button = controls.getChildAt(i);
                setDimLevel(button, color);
            }

//            ViewGroup extracontrols = findViewById(R.id.slide_menu);
//            for (int i = 0; i < extracontrols.getChildCount(); i++) {
//                View button = extracontrols.getChildAt(i);
//                setDimLevel(button, color);
//            }

            ReaderActivity.this.getWindow().setBackgroundDrawable(null);
            webView.setBackgroundColor(color);
            ReaderActivity.this.getWindow().setBackgroundDrawable(new ColorDrawable(color));

            if (!controlsonly) {
                //Log.d("GG", String.format("#%6X", color & 0xFFFFFF));
                webView.getSettings().setJavaScriptEnabled(true);
                webView.evaluateJavascript("(function(){var newSS, styles='* { background: " + String.format("#%6X", color & 0xFFFFFF) + " ! important; color: black !important } :link, :link * { color: #000088 !important } :visited, :visited * { color: #44097A !important }'; if(document.createStyleSheet) {document.createStyleSheet(\"javascript:'\"+styles+\"'\");} else { newSS=document.createElement('link'); newSS.rel='stylesheet'; newSS.href='data:text/css,'+escape(styles); document.getElementsByTagName(\"head\")[0].appendChild(newSS); } })();", null);
                webView.getSettings().setJavaScriptEnabled(false);
            }
        } catch (Throwable t) {
            Log.e(TAG, t.getMessage(), t);
            Toast.makeText(this, t.getLocalizedMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setDimLevel(View button, int color) {
        try {
            button.setBackground(null);
            Drawable btn = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                btn = getResources().getDrawable(android.R.drawable.btn_default, null).mutate();
            } else {
                btn = getResources().getDrawable(android.R.drawable.btn_default).mutate();
            }
            btn.setColorFilter(color, PorterDuff.Mode.MULTIPLY);
            button.setBackground(btn);
            if (button instanceof ImageButton) {
                ((ImageButton) button).getDrawable().mutate().setColorFilter(color, PorterDuff.Mode.MULTIPLY);
            }
        } catch (Throwable t) {
            Log.e(TAG, t.getMessage(), t);
        }
    }
}


activity_reader.xml

    <?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
        xmlns:app="http://schemas.android.com/apk/res-auto"
        xmlns:tools="http://schemas.android.com/tools"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        tools:context="com.quaap.bookymcbookface.ReaderActivity">


<FrameLayout
        android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:layout_above="@+id/controls_layout"
                android:layout_alignParentTop="true"
                android:layout_centerHorizontal="true"
                android:layout_centerVertical="true">

<WebView
            android:id="@+id/page_view"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:layout_margin="8dp">

</WebView>

<ProgressBar
            android:id="@+id/progressBar"
                    style="?android:attr/progressBarStyle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center"
                    android:visibility="gone" />

<LinearLayout
            android:id="@+id/slide_menu"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:visibility="gone"
                    android:elevation="2dp"
                    android:layout_gravity="bottom|center_horizontal">

<Button
                android:id="@+id/contents_button"
                        android:layout_width="match_parent"
                        android:layout_height="@android:dimen/app_icon_size"
                        android:layout_marginBottom="12dp"
                        android:layout_weight="1"
                        android:gravity="center"
                        android:text="@string/contents" />

<ImageButton
                android:id="@+id/brightness_button"
                        android:layout_width="match_parent"
                        android:layout_height="@android:dimen/app_icon_size"
                        android:layout_marginBottom="12dp"
                        android:layout_weight="1"
                        android:contentDescription="@string/zoom"
                        android:src="@drawable/light" />

<LinearLayout
                android:layout_width="match_parent"
                        android:layout_height="@android:dimen/app_icon_size"
                        android:layout_marginBottom="12dp"
                        android:layout_weight="1"
                        android:background="@android:drawable/btn_default"
                        android:orientation="horizontal">

<CheckBox
                    android:id="@+id/fullscreen_box"
                            android:layout_width="wrap_content"
                            android:layout_height="25dp" />

<ImageButton
                    android:id="@+id/fullscreen_button"
                            android:layout_width="25dp"
                            android:layout_height="25dp"
                            android:contentDescription="@string/zoom"
                            android:src="@drawable/fullscreen"
                            android:visibility="visible" />
</LinearLayout>

<ImageButton
                android:id="@+id/zoom_button"
                        android:layout_width="match_parent"
                        android:layout_height="@android:dimen/app_icon_size"
                        android:layout_marginBottom="12dp"
                        android:layout_weight="1"
                        android:contentDescription="@string/zoom"
                        android:src="@android:drawable/ic_menu_zoom" />

</LinearLayout>
</FrameLayout>

<LinearLayout
        android:id="@+id/controls_layout"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_alignParentBottom="true"
                android:layout_centerHorizontal="true"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:padding="1dp">

<Button
            android:id="@+id/prev_button"
                    android:layout_width="wrap_content"
                    android:layout_height="34dp"
                    android:layout_gravity="center_vertical"
                    android:layout_weight="1"
                    android:gravity="center"
                    android:padding="3dp"
                    android:text="@string/prev_text"
                    android:textStyle="bold" />


<ImageButton
            android:id="@+id/control_view_more"
                    android:layout_width="wrap_content"
                    android:layout_height="34dp"
                    android:layout_gravity="center_vertical"
                    android:layout_weight="1"
                    android:padding="3dp"
                    android:src="@android:drawable/arrow_up_float" />

            <ImageButton
            android:id="@+id/control_view_less"
                    android:layout_width="wrap_content"
                    android:layout_height="34dp"
                    android:layout_gravity="center_vertical"
                    android:layout_weight="1"
                    android:padding="3dp"
                    android:src="@android:drawable/arrow_down_float"
                    android:visibility="gone" />

            <Button
            android:id="@+id/next_button"
                    android:layout_width="wrap_content"
                    android:layout_height="34dp"
                    android:layout_gravity="center_vertical"
                    android:layout_weight="1"
                    android:gravity="center"
                    android:padding="3dp"
                    android:text="@string/next_text"
                    android:textStyle="bold" />
</LinearLayout>

</RelativeLayout>




