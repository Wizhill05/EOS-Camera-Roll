package dev.aryan.cameralinklab;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public final class GalleryMediaChecks {
    private static int checks;
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    private static Object json(String value){return new JSONTokener(value).nextValue();}
    public static void main(String[] args)throws Exception {catalog();partialCatalog();culling();orientationAndIssues();transfers();cameraQueue();smallDeadlines();catalogDisconnect();pairingAndRatings();System.out.println("All "+checks+" media checks passed.");}
    private static final String PREFIX="/ccapi/ver140/contents";
    private static final String PHOTOS=PREFIX+"/card1/DCIM/100CANON";
    private static final String VIDEOS=PREFIX+"/card1/XFVC/REEL_0011";
    private static Map<String,Object> fixture(){
        Map<String,Object> data=new HashMap<>();
        data.put("/ccapi",json("{\"value\":\"No list of APIs\"}"));
        data.put("/ccapi/ver100/topurlfordev",json("{\"ver100\":[{\"url\":\"/ccapi/ver100/contents\",\"get\":true}],\"ver140\":[{\"url\":\"contents\",\"get\":true}]}"));
        data.put(PREFIX,links(PREFIX+"/card1"));data.put(PREFIX+"/card1",links(PHOTOS,VIDEOS,"http://unrelated/ccapi/ver140/contents/card1/evil"));
        data.put(PHOTOS,links(PHOTOS+"/IMG_0001.JPG",PHOTOS));
        data.put(PHOTOS+"?kind=number",json("{\"pagenumber\":2}"));
        data.put(PHOTOS+"?page=1",links(PHOTOS+"/IMG_0001.JPG"));
        data.put(PHOTOS+"?page=2",links(PHOTOS+"/IMG_0002.CR3",PHOTOS+"/metadata.XML"));
        data.put(VIDEOS,links(VIDEOS+"/A_0011.MP4"));data.put(VIDEOS+"?kind=number",json("{\"pagenumber\":1}"));data.put(VIDEOS+"?page=1",links(VIDEOS+"/A_0011.MP4"));return data;
    }
    private static Object links(String... paths){return new JSONObject().put("path",new JSONArray(Arrays.asList(paths)));}
    private static MediaCatalog.Transport transport(Map<String,Object> fixture,Set<String> requested){return url->{String key=url.getFile();requested.add(key);Object value=fixture.get(key);if(value==null)throw new IOException("Missing fixture "+key);return value;};}
    private static void catalog()throws Exception{
        Map<String,Object> fixture=fixture();Set<String> requested=new HashSet<>();
        MediaCatalog catalog=new MediaCatalog(new URL("http://camera:8080/ccapi"),transport(fixture,requested),()->false,s->{});
        List<MediaItem> items=catalog.scan(partial->{});
        check(items.size()==3,"Walk all folders and listing pages without duplicates");
        check(items.stream().filter(i->i.video).count()==1,"Include XFVC movie alongside photos");
        check(items.stream().anyMatch(i->i.raw()&&i.name.equals("IMG_0002.CR3")),"Find CR3 on the second page");
        check(items.stream().noneMatch(i->i.name.endsWith("XML")),"Keep catalog scoped to images and videos");
        check(requested.stream().noneMatch(p->p.contains("evil")),"Never request a cross-origin camera link");
        check(requested.contains(PREFIX)&&!requested.contains("/ccapi/ver100/contents"),"Select highest advertised readable contents version");
        check(catalog.warnings.size()==1,"Report rejected links in scan diagnostics");
        MediaItem named=new MediaItem(new URL("http://camera:8080"+PHOTOS+"/Photo%20A%2B1.JPG"));check(named.name.equals("Photo A+1.JPG"),"Decode filenames while preserving plus signs");
        MediaCatalog stopped=new MediaCatalog(new URL("http://camera:8080/ccapi"),transport(fixture,new HashSet<>()),()->true,s->{});
        try{stopped.scan(partial->{});throw new AssertionError("Cancellation ignored");}catch(IOException expected){check(true,"Cancel recursive enumeration");}
    }
    private static void partialCatalog()throws Exception{
        Map<String,Object> fixture=fixture();fixture.remove(VIDEOS);MediaCatalog catalog=new MediaCatalog(new URL("http://camera:8080/ccapi"),transport(fixture,new HashSet<>()),()->false,s->{});
        List<MediaItem> items=catalog.scan(partial->{});check(items.size()==2&&catalog.warnings.stream().anyMatch(s->s.contains("Could not read")),"Expose partial library after a folder error");
    }
    private static void culling()throws Exception{
        String first=MediaKey.of("serial:r50v","/ccapi/ver100/contents/card1/DCIM/100CANON/IMG_0001.CR3");
        check(first.equals(MediaKey.of("serial:r50v","/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_0001.CR3")),"Keep ratings and save receipts stable across API versions");
        check(!first.equals(MediaKey.of("serial:other","/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_0001.CR3")),"Keep cameras with the same filenames separate");
        check(!first.equals(MediaKey.of("serial:r50v","/ccapi/ver140/contents/card1/DCIM/101CANON/IMG_0001.CR3")),"Keep identical filenames in separate folders distinct");
        MediaItem identityItem=new MediaItem(new URL("http://camera/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_0001.CR3"));String identityKey=identityItem.keyFor("serial:r50v");check(!identityKey.equals(identityItem.keyFor("serial:other"))&&identityKey.equals(identityItem.keyFor("serial:r50v")),"Keep memoized media keys correct when the camera identity changes");
        check(MediaKey.matchesRating(0,0)&&MediaKey.matchesRating(5,0),"All ratings includes unrated media");
        check(MediaKey.matchesRating(4,4)&&MediaKey.matchesRating(5,4)&&!MediaKey.matchesRating(3,4)&&!MediaKey.matchesRating(0,4),"Minimum star filter includes the threshold and higher ratings");
    }
    private static void orientationAndIssues()throws Exception{
        byte[] tiff={'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        check(ExifOrientation.read(tiff)==6,"Read original portrait EXIF independently of thumbnail JPEG metadata");
        ByteArrayOutputStream jpg=new ByteArrayOutputStream();jpg.write(new byte[]{(byte)255,(byte)216,(byte)255,(byte)225,0,34,'E','x','i','f',0,0});jpg.write(tiff);jpg.write(new byte[]{(byte)255,(byte)217});check(ExifOrientation.read(jpg.toByteArray())==6,"Read JPEG APP1 portrait EXIF");
        ByteArrayOutputStream cr3=new ByteArrayOutputStream();cr3.write(new byte[]{0,0,0,34,'C','M','T','1'});cr3.write(tiff);check(ExifOrientation.read(cr3.toByteArray())==6,"Read bounded Canon CR3 CMT1 orientation");
        byte[] invalid=tiff.clone();invalid[4]=(byte)255;check(ExifOrientation.read(invalid)==0,"Reject EXIF directory offsets outside the bounded header");
        check(ExifOrientation.effective(0,6,80,50)==6,"Rotate a landscape thumbnail using original portrait metadata");
        check(ExifOrientation.effective(1,6,50,80)==1,"Avoid rotating thumbnail pixels that are already portrait");
        check(ExifOrientation.effective(8,6,80,50)==8,"Honor embedded EXIF before camera fallback metadata");
        check(ExifOrientation.fromDegrees(90)==6&&ExifOrientation.fromDegrees(270)==8&&ExifOrientation.fromDegrees(13)==0,"Accept only supported camera rotation angles");
        check(LibraryIssue.from(new UnknownHostException()).title.equals("Device not found"),"Distinguish a missing hostname from an empty camera card");
        check(LibraryIssue.from(new SocketTimeoutException()).title.equals("Camera out of reach"),"Explain unreachable cameras without claiming the card is empty");
        check(LibraryIssue.from(new CameraHttp.StatusException(403,"Denied")).title.equals("Camera refused access"),"Distinguish reachable camera access rejection");
        check(LibraryIssue.from(new CameraHttp.StatusException(404,"Missing")).title.equals("Camera API not found"),"Distinguish a camera endpoint error from network failure");
        check(LibraryIssue.from(new IOException("HTTP 401 rejected")).title.equals("Camera sign-in required"),"Explain account rejection separately from camera reachability");
    }
    private static void transfers()throws Exception{
        byte[] media=new byte[1200000];for(int i=0;i<media.length;i++)media[i]=(byte)(i%251);
        AtomicInteger requests=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PREFIX+"/card1/XFVC/clip.MP4",exchange->{requests.incrementAndGet();if(exchange.getRequestHeaders().containsKey("Range"))throw new AssertionError("Range sent");exchange.sendResponseHeaders(200,media.length);try{exchange.getResponseBody().write(media);}finally{exchange.close();}});server.start();String origin="http://127.0.0.1:"+server.getAddress().getPort();
        try(CameraSession session=new CameraSession(new URL(origin+"/ccapi"),null,"","",s->{})){
            MediaItem original=new MediaItem(new URL(origin+PREFIX+"/card1/XFVC/clip.MP4"));ByteArrayOutputStream saved=new ByteArrayOutputStream();CameraSession.SaveResult result=session.save(original,saved,()->false,(done,total)->{});
            check(Arrays.equals(media,saved.toByteArray())&&result.bytes==media.length,"Save complete original video unchanged using one normal GET");
            check(result.hash.equals(CameraHttp.hex(java.security.MessageDigest.getInstance("SHA-256").digest(media))),"Validate original video hash independently");
            MediaItem foreign=new MediaItem(new URL("http://unrelated.invalid/ccapi/ver140/contents/card1/DCIM/foreign.JPG"));
            try{session.save(foreign,new ByteArrayOutputStream(),()->false,(n,total)->{});throw new AssertionError("Foreign original accepted");}catch(IOException expected){check(true,"Reject foreign-origin original before credentials or network access");}
            try{session.save(original,new ByteArrayOutputStream(),()->true,(done,total)->{});throw new AssertionError("Cancelled save succeeded");}catch(IOException expected){check(requests.get()==1,"A cancelled save sends no camera request");}
        }finally{server.stop(0);}
    }
    private static HttpURLConnection empty(URL url,AtomicInteger opened,AtomicInteger current,AtomicInteger maximum){return new HttpURLConnection(url){
        boolean disconnected;
        public void connect(){}public boolean usingProxy(){return false;}
        public int getResponseCode(){opened.incrementAndGet();maximum.accumulateAndGet(current.incrementAndGet(),Math::max);return 200;}
        public void disconnect(){if(!disconnected){disconnected=true;current.decrementAndGet();}}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[]{1});}
    };}
    private static void cameraQueue()throws Exception{
        URL url=new URL("http://queue-camera/ccapi");AtomicInteger opened=new AtomicInteger(),current=new AtomicInteger(),maximum=new AtomicInteger();CameraHttp.Opener opener=u->empty(u,opened,current,maximum);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();CameraHttp first=new CameraHttp(opener,"",""),waiting=new CameraHttp(opener,"","");
        try{
            first.get(url);java.util.concurrent.Future<Boolean> blocked=worker.submit(()->{try{waiting.get(url);return false;}catch(IOException expected){return true;}finally{waiting.close();}});
            try{blocked.get(150,java.util.concurrent.TimeUnit.MILLISECONDS);throw new AssertionError("Requests overlapped");}catch(java.util.concurrent.TimeoutException expected){check(opened.get()==1,"Serialize independent clients to the same camera through body cleanup");}
            waiting.cancel();check(blocked.get(1,java.util.concurrent.TimeUnit.SECONDS)&&opened.get()==1,"Cancel a queued request without opening another socket");
            first.close();try(CameraHttp next=new CameraHttp(opener,"","")){next.get(url);next.read(10);}check(maximum.get()==1&&current.get()==0,"Release camera ownership after close so a fresh request succeeds");
        }finally{first.close();waiting.close();worker.shutdownNow();}
        java.util.concurrent.CountDownLatch reading=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);AtomicInteger sockets=new AtomicInteger();
        CameraHttp stalled=new CameraHttp(target->{sockets.incrementAndGet();return new HttpURLConnection(target){public void connect(){}public boolean usingProxy(){return false;}public int getResponseCode(){return 200;}public void disconnect(){try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}public InputStream getInputStream(){return new InputStream(){public int read()throws IOException{reading.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}return -1;}};}};},"","");
        java.util.concurrent.ExecutorService reader=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            java.util.concurrent.Future<?> pending=reader.submit(()->{try{stalled.get(url);stalled.read(100);}catch(IOException expected){}finally{stalled.close();}});check(reading.await(1,java.util.concurrent.TimeUnit.SECONDS),"Exercise cancellation with an active stalled body");
            long start=System.nanoTime();stalled.cancel();check(System.nanoTime()-start<250000000L,"Cancellation returns without waiting for socket shutdown");
            try(CameraHttp next=new CameraHttp(opener,"","").timeouts(200,200)){try{next.get(url);throw new AssertionError("Cancelled body ownership released early");}catch(SocketTimeoutException expected){check(sockets.get()==1&&opened.get()==2,"Do not start a new request while an old response is still closing");}}
            release.countDown();pending.get(1,java.util.concurrent.TimeUnit.SECONDS);try(CameraHttp next=new CameraHttp(opener,"","")){next.get(url);check(next.read(10).length==1,"Camera queue recovers after cancelled response cleanup");}
        }finally{release.countDown();reader.shutdownNow();}
    }
    private static void smallDeadlines()throws Exception{
        URL url=new URL("http://deadline-camera/ccapi");AtomicInteger bodyReads=new AtomicInteger();
        try(CameraHttp client=new CameraHttp(target->new HttpURLConnection(target){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}public int getResponseCode(){return 200;}public long getContentLengthLong(){return 1000000000L;}public InputStream getInputStream(){bodyReads.incrementAndGet();return new ByteArrayInputStream(new byte[1]);}},"","")){
            HttpURLConnection response=client.get(url);check("close".equals(response.getRequestProperty("Connection")),"Close each camera HTTP connection instead of retaining pooled responses");
            try{client.read(65536);throw new AssertionError("Oversized body read");}catch(CameraHttp.ResponseTooLargeException expected){check(bodyReads.get()==0,"Reject oversized previews before opening their response body");}
        }
        try(CameraHttp client=new CameraHttp(target->new HttpURLConnection(target){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}public int getResponseCode(){return 200;}public InputStream getInputStream(){return new InputStream(){public int read(){try{Thread.sleep(40);}catch(InterruptedException e){Thread.currentThread().interrupt();}return 1;}public int read(byte[] bytes,int off,int count){bytes[off]=(byte)read();return 1;}};}},"","").deadline(120)){
            client.get(url);long started=System.nanoTime();try{client.read(65536);throw new AssertionError("Trickling body exceeded deadline");}catch(SocketTimeoutException expected){check(System.nanoTime()-started<1500000000L,"An absolute preview deadline stops a trickling response");}
        }
    }
    private static void catalogDisconnect()throws Exception{
        Map<String,Object> fixture=fixture();Set<String> requested=new HashSet<>();MediaCatalog.Transport good=transport(fixture,requested);
        MediaCatalog catalog=new MediaCatalog(new URL("http://camera:8080/ccapi"),url->{if(url.getPath().equals(PHOTOS)){requested.add(url.getFile());throw new SocketTimeoutException("camera stopped answering");}return good.json(url);},()->false,text->{});
        try{catalog.scan(partial->{});throw new AssertionError("Disconnected scan continued");}catch(SocketTimeoutException expected){check(!requested.contains(VIDEOS),"Stop folder enumeration after transport loss instead of hammering remaining folders");}
    }
    private static void pairingAndRatings()throws Exception{
        URL root=new URL("http://camera/ccapi");MediaItem jpeg=new MediaItem(new URL(root,PHOTOS+"/IMG_0001.JPG")),raw=new MediaItem(new URL(root,PHOTOS+"/IMG_0001.CR3")),other=new MediaItem(new URL(root,PHOTOS+"/sub/IMG_0001.CR3"));List<MediaItem> files=Arrays.asList(raw,jpeg,other);MediaPairs pairs=new MediaPairs(files);
        check(pairs.representatives(files,0).size()==2&&pairs.thumbnail(raw).equals(jpeg),"Group same-folder RAW/JPEG once and use JPEG as its thumbnail");check(pairs.representatives(files,1).size()==2&&pairs.representatives(files,2).equals(Collections.singletonList(jpeg)),"Filter RAW and JPEG without merging different folders");check(pairs.expand(Collections.singletonList(jpeg)).size()==2,"Expand a paired bulk save to both originals");
        Map<String,Object> data=fixture();((JSONObject)data.get("/ccapi/ver100/topurlfordev")).getJSONArray("ver140").getJSONObject(0).put("put",true);final String[] writable={""};Set<String> seen=new HashSet<>();MediaCatalog.Transport reader=transport(data,seen);
        MediaCatalog catalog=new MediaCatalog(new URL("http://camera:8080/ccapi"),new MediaCatalog.Transport(){public Object json(URL url)throws IOException{return reader.json(url);}public void writableContents(String path){writable[0]=path;}},()->false,message->{});catalog.scan(partial->{});check(writable[0].equals(PREFIX),"Use the camera-advertised contents PUT capability");
        AtomicInteger rating=new AtomicInteger(),puts=new AtomicInteger();java.util.concurrent.atomic.AtomicBoolean authRequired=new java.util.concurrent.atomic.AtomicBoolean(),ignoreWrite=new java.util.concurrent.atomic.AtomicBoolean();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext(PHOTOS+"/IMG_0001.JPG",exchange->{try{exchange.getResponseHeaders().set("Connection","close");if(authRequired.get()&&!"Basic dXNlcjpwYXNz".equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.getResponseHeaders().set("WWW-Authenticate","Basic realm=\"camera\"");exchange.sendResponseHeaders(401,-1);return;}if(exchange.getRequestMethod().equals("PUT")){JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));if(!body.getString("action").equals("rating"))throw new AssertionError("Wrong rating action");String value=body.getString("value");if(!ignoreWrite.get())rating.set(value.equals("off")?0:Integer.parseInt(value));puts.incrementAndGet();}byte[] response=("{\"rating\":\""+(rating.get()==0?"off":rating.get())+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}catch(Throwable error){error.printStackTrace();throw new IOException(error);}finally{exchange.close();}});server.start();String origin="http://127.0.0.1:"+server.getAddress().getPort();
        try(CameraSession source=new CameraSession(new URL(origin+"/ccapi"),null,"","",message->{})){
            MediaItem item=new MediaItem(new URL(origin+PHOTOS+"/IMG_0001.JPG"));try{source.rate(item,4);throw new AssertionError("Unadvertised write accepted");}catch(IOException expected){check(puts.get()==0,"Do not send unadvertised camera rating writes");}source.writableContents=PREFIX;source.rate(item,4);check(rating.get()==4&&puts.get()==1,"Write Canon rating action and confirm it through file-info readback");ignoreWrite.set(true);try{source.rate(item,2);throw new AssertionError("Mismatched camera rating accepted");}catch(IOException expected){check(rating.get()==4,"Never confirm an ignored or mismatched camera rating write");}ignoreWrite.set(false);source.rate(item,0);check(rating.get()==0,"Clear camera rating using off and verify readback");
            authRequired.set(true);try(CameraSession authenticated=new CameraSession(new URL(origin+"/ccapi"),null,"user","pass",message->{})){authenticated.writableContents=PREFIX;authenticated.rate(item,5);check(rating.get()==5,"Authenticate a camera rating PUT and its GET readback");}
        }finally{server.stop(0);}
    }

}
