package dev.aryan.cameralinklab;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.function.*;
import org.json.*;

final class MediaCatalog {
    interface Transport { Object json(URL url) throws IOException; default void cameraIdentity(String id){} default void writableContents(String path){} }
    private final URL origin;
    private final Transport transport;
    private final BooleanSupplier cancelled;
    private final Consumer<String> log;
    final List<String> warnings = new ArrayList<>();

    MediaCatalog(URL origin, Transport transport, BooleanSupplier cancelled, Consumer<String> log) {
        this.origin=origin;this.transport=transport;this.cancelled=cancelled;this.log=log;
    }

    List<MediaItem> scan(Consumer<List<MediaItem>> progress) throws IOException {
        checkCancelled();
        Object table=transport.json(resolve("/ccapi"));
        List<URL> roots=contentRoots(table);
        if(roots.isEmpty()) {
            log.accept("Discovering camera media endpoints");
            table=transport.json(resolve("/ccapi/ver100/topurlfordev"));roots=contentRoots(table);
        }
        if(roots.isEmpty())throw new IOException("The camera did not advertise a readable media library.");
        List<URL> identities=new ArrayList<>();readApiEntries(table,"",identities,"deviceinformation");
        if(!identities.isEmpty())try{Object info=transport.json(identities.get(0));if(info instanceof JSONObject){String serial=((JSONObject)info).optString("serialnumber","");if(!serial.isEmpty())transport.cameraIdentity("serial:"+serial);}}catch(IOException error){if(LibraryIssue.connectionLost(error))throw error;log.accept("Camera identity unavailable; labels use the camera address.");}
        roots.sort(Comparator.comparingInt((URL u)->version(u.getPath())).reversed());
        URL root=roots.get(0);List<URL> writable=new ArrayList<>();readApiEntries(table,"",writable,"contents","put");writable.sort(Comparator.comparingInt((URL u)->version(u.getPath())).reversed());transport.writableContents(writable.isEmpty()?"":writable.get(0).getPath());
        ArrayDeque<URL> pending=new ArrayDeque<>();pending.add(root);
        Set<String> seen=new HashSet<>();LinkedHashMap<String,MediaItem> items=new LinkedHashMap<>();
        while(!pending.isEmpty()) {
            checkCancelled();URL folder=pending.removeFirst();
            if(!seen.add(folder.getPath()))continue;
            if(seen.size()>10000){warnings.add("Folder scan limit reached; some media may be missing.");break;}
            log.accept("Reading "+folder.getPath().replaceFirst("^/ccapi/ver\\d+/contents/?", ""));
            try {
                for(String path:folderLinks(folder)) {
                    URL child;
                    try {child=resolve(path);}catch(IOException invalid){warnings.add("Ignored an invalid camera link.");continue;}
                    String prefix=root.getPath()+"/";
                    if(!child.getPath().startsWith(prefix))continue;
                    if(MediaItem.isMedia(child.getPath()))items.put(child.toExternalForm(),new MediaItem(child));
                    else if(!MediaItem.hasExtension(child.getPath()))pending.addLast(child);
                }
            }catch(IOException error) {
                if(cancelled.getAsBoolean()||LibraryIssue.connectionLost(error))throw error;
                if(folder.equals(root))throw error;
                warnings.add("Could not read "+folder.getPath()+": "+error.getMessage());
            }
            progress.accept(new ArrayList<>(items.values()));
        }
        return new ArrayList<>(items.values());
    }

    private List<String> folderLinks(URL folder) throws IOException {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        Object first=transport.json(folder);collect(first,result);
        long pages=pageCount(first);
        // Canon lists may paginate without putting page links in the first response.
        if(pages<0 && result.stream().anyMatch(p -> MediaItem.isMedia(p.split("\\?",2)[0]))) {
            try {pages=pageCount(transport.json(query(folder,"kind=number")));}
            catch(CameraHttp.StatusException e){if(e.status!=400&&e.status!=404&&e.status!=405)throw e;pages=0;}
        }
        if(pages>10000)throw new IOException("Camera reported too many listing pages");
        if(pages>0)for(int page=1;page<=pages;page++) {
            checkCancelled();collect(transport.json(query(folder,"page="+page)),result);
        }
        return new ArrayList<>(result);
    }
    private void checkCancelled() throws IOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("Cancelled");}
    private static void collect(Object json,Set<String> out)throws IOException {try{CcapiLinks.collect(json,out);}catch(JSONException e){throw new IOException("Invalid camera link list",e);}}
    private static long pageCount(Object json)throws IOException {
        if(!(json instanceof JSONObject))return -1;
        JSONObject object=(JSONObject)json;if(!object.has("pagenumber"))return -1;
        Object value=object.opt("pagenumber");
        if(!(value instanceof Number))throw new IOException("Invalid listing page count");
        long count=((Number)value).longValue();if(count<0)throw new IOException("Invalid negative page count");return count;
    }

    private List<URL> contentRoots(Object json)throws IOException {
        List<URL> found=new ArrayList<>();readApiEntries(json,"",found,"contents");return found;
    }
    private void readApiEntries(Object json,String ver,List<URL> roots,String leaf){readApiEntries(json,ver,roots,leaf,"get");}
    private void readApiEntries(Object json,String ver,List<URL> roots,String leaf,String method) {
        if(json instanceof JSONObject) {
            JSONObject object=(JSONObject)json;
            Object get=object.opt(method);
            if(get!=null&&get!=JSONObject.NULL&&!get.toString().matches("(?i)false|0|no|none|unsupported|")) {
                String path=object.optString("url",object.optString("path",""));
                if(!path.isEmpty())try {
                    if(!path.startsWith("/ccapi/")&&!path.matches("https?://.*")&&!ver.isEmpty())path="/ccapi/"+ver+"/"+path.replaceFirst("^/","");
                    URL u=resolve(path);if(u.getPath().matches("/ccapi/ver\\d+/"+leaf))roots.add(u);
                }catch(IOException ignored){}
            }
            Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();readApiEntries(object.opt(key),key.matches("ver\\d+")?key:ver,roots,leaf,method);}
        }else if(json instanceof JSONArray){JSONArray array=(JSONArray)json;for(int i=0;i<array.length();i++)readApiEntries(array.opt(i),ver,roots,leaf,method);}
    }
    URL resolve(String path)throws IOException {
        URL u=CameraHttp.url(new URL(origin,path).toString());
        if(!CameraHttp.sameOrigin(origin,u))throw new IOException("Camera link points to another server");
        if(u.getPath().contains("/../")||u.getPath().contains("/./"))throw new IOException("Invalid camera path");
        try{return new URL(u.getProtocol(),u.getHost(),u.getPort(),u.getPath());}catch(MalformedURLException e){throw new IOException(e);}
    }
    static URL query(URL u,String query)throws MalformedURLException {return new URL(u.getProtocol(),u.getHost(),u.getPort(),u.getPath()+"?"+query);}
    private static int version(String path){try{return Integer.parseInt(path.split("/")[2].substring(3));}catch(Exception e){return 0;}}
}
