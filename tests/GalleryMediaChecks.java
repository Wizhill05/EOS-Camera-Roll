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
    public static void main(String[] args)throws Exception {catalog();partialCatalog();culling();orientationAndIssues();streaming();videoCancellation();System.out.println("All "+checks+" media checks passed.");}
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
    private static void streaming()throws Exception{
        byte[] media=new byte[1_200_000];for(int i=0;i<media.length;i++)media[i]=(byte)(i%251);
        AtomicInteger rangeRequests=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ccapi/range.MP4",exchange->{String range=exchange.getRequestHeaders().getFirst("Range");rangeRequests.incrementAndGet();String[] parts=range.substring(6).split("-");int start=Integer.parseInt(parts[0]),end=Math.min(media.length-1,Integer.parseInt(parts[1]));exchange.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+media.length);exchange.sendResponseHeaders(206,end-start+1);exchange.getResponseBody().write(media,start,end-start+1);exchange.close();});
        server.createContext("/ccapi/sequential.MP4",exchange->{exchange.sendResponseHeaders(200,media.length);try{exchange.getResponseBody().write(media);}finally{exchange.close();}});
        server.createContext("/ccapi/wrong.MP4",exchange->{exchange.getResponseHeaders().set("Content-Range","bytes 1-9/100");exchange.sendResponseHeaders(206,9);exchange.getResponseBody().write(new byte[9]);exchange.close();});
        server.createContext("/ccapi/huge.MP4",exchange->{String[] range=exchange.getRequestHeaders().getFirst("Range").substring(6).split("-");long start=Long.parseLong(range[0]),end=Math.min(7_999_999_999L,Long.parseLong(range[1]));byte[] chunk=new byte[(int)(end-start+1)];for(int i=0;i<chunk.length;i++)chunk[i]=(byte)((start+i)%251);exchange.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/8000000000");exchange.sendResponseHeaders(206,chunk.length);exchange.getResponseBody().write(chunk);exchange.close();});
        server.start();File directory=Files.createTempDirectory("camera-media-check").toFile();String origin="http://127.0.0.1:"+server.getAddress().getPort();
        try{
            File rangeTemp=new File(directory,"range.cache");try(RemoteMediaFile source=new RemoteMediaFile(new URL(origin+"/ccapi/range.MP4"),URL::openConnection,"","",s->{})){
                source.prepare();check(source.supportsRanges()&&source.size()==media.length,"Recognize validated camera byte ranges");
                byte[] chunk=new byte[1500];int read=source.readAt(900000,chunk,0,chunk.length);check(read==chunk.length&&Arrays.equals(chunk,Arrays.copyOfRange(media,900000,901500)),"Seek to uncached position with exact byte fidelity");
                int requests=rangeRequests.get();source.readAt(901000,chunk,0,100);check(rangeRequests.get()==requests,"Reuse cached range for adjacent reads");
                source.readAt(0,chunk,0,100);source.readAt(901000,chunk,0,100);check(rangeRequests.get()==requests+1,"Retain header and tail blocks when the decoder switches between them");
                check(source.readAt(media.length,chunk,0,1)==-1,"Return EOF at file boundary");
            }check(!rangeTemp.exists(),"Avoid full-file disk cache with byte ranges");
            try(RemoteMediaFile source=new RemoteMediaFile(new URL(origin+"/ccapi/huge.MP4"),URL::openConnection,"","",s->{})){
                source.prepare();byte[] bytes=new byte[2048];long offset=5_000_000_123L;int count=source.readAt(offset,bytes,0,bytes.length);boolean correct=count==bytes.length&&source.size()==8_000_000_000L;for(int i=0;i<count;i++)correct&=bytes[i]==(byte)((offset+i)%251);check(correct,"Preserve video sizes and seek offsets above 4 GiB");
                boolean limited=false;for(int i=0;i<40;i++){try{source.readAt(i*262144L,bytes,0,1);}catch(IOException error){limited=error.getMessage().contains("streaming limit");break;}}
                check(limited,"Cap camera reads before the first video frame instead of buffering indefinitely");source.playbackStarted();check(source.readAt(40*262144L,bytes,0,1)==1,"Allow continued on-demand playback after the first frame");
            }
            File spool=new File(directory,"spool.cache");try(RemoteMediaFile source=new RemoteMediaFile(new URL(origin+"/ccapi/sequential.MP4"),URL::openConnection,"","",s->{})){
                try{source.prepare();throw new AssertionError("Ignored Range started streaming");}catch(IOException expected){check(expected.getMessage().contains("Download & play"),"Refuse ignored ranges instead of silently downloading the entire video");}
            }check(!spool.exists(),"Never create a sequential video spool");
            try(CameraSession session=new CameraSession(new URL(origin+"/ccapi"),null,"","",s->{})){
                MediaItem original=new MediaItem(new URL(origin+PREFIX+"/card1/XFVC/clip.MP4"));
                server.createContext(PREFIX+"/card1/XFVC/clip.MP4",exchange->{exchange.sendResponseHeaders(200,media.length);try{exchange.getResponseBody().write(media);}finally{exchange.close();}});
                ByteArrayOutputStream saved=new ByteArrayOutputStream();CameraSession.SaveResult result=session.save(original,saved,()->false,(done,total)->{});
                check(Arrays.equals(media,saved.toByteArray())&&result.bytes==media.length,"Save original bytes unchanged through the gallery transfer engine");
                check(result.hash.equals(CameraHttp.hex(java.security.MessageDigest.getInstance("SHA-256").digest(media))),"Validate the downloaded original hash independently");
                MediaItem foreign=new MediaItem(new URL("http://unrelated.invalid/ccapi/ver140/contents/card1/DCIM/foreign.JPG"));
                try{session.save(foreign,new ByteArrayOutputStream(),()->false,(n,total)->{});throw new AssertionError("Foreign original origin accepted");}catch(IOException expected){check(true,"Reject foreign-origin original transfers before sending credentials");}
                try{session.save(original,new ByteArrayOutputStream(),()->true,(done,total)->{});throw new AssertionError("Cancelled save succeeded");}catch(IOException expected){check(true,"Abort original download when cancelled");}
            }
            try(RemoteMediaFile source=new RemoteMediaFile(new URL(origin+"/ccapi/wrong.MP4"),URL::openConnection,"","",s->{})){
                try{source.prepare();throw new AssertionError("Wrong Content-Range accepted");}catch(IOException expected){check(true,"Reject mismatched video byte range");}
            }
        }finally{server.stop(0);File[] files=directory.listFiles();if(files!=null)for(File f:files)f.delete();directory.delete();}
    }
    private static void videoCancellation()throws Exception{
        AtomicInteger opens=new AtomicInteger(),bodyReads=new AtomicInteger();URL url=new URL("http://camera/ccapi/test.MP4");
        CameraHttp.Opener ignoresRange=target->{opens.incrementAndGet();return new HttpURLConnection(target){
            public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
            public int getResponseCode(){return 200;}
            public InputStream getInputStream(){bodyReads.incrementAndGet();return new ByteArrayInputStream(new byte[1024*1024]);}
        };};
        RemoteMediaFile refused=new RemoteMediaFile(url,ignoresRange,"","",text->{});
        try{refused.prepare();throw new AssertionError("Ignored Range accepted");}catch(IOException expected){}finally{refused.close();}
        check(opens.get()==1&&bodyReads.get()==0,"Do not read a single full-response body when the camera ignores Range");
        java.util.concurrent.CountDownLatch reading=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        CameraHttp.Opener stalled=target->{opens.incrementAndGet();return new HttpURLConnection(target){
            public void connect(){}public boolean usingProxy(){return false;}
            public void disconnect(){if(getRequestProperty("Range").equals("bytes=0-0"))return;try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            public int getResponseCode(){return 206;}
            public String getHeaderField(String name){if(!name.equals("Content-Range"))return null;String range=getRequestProperty("Range");return "bytes "+range.substring(6)+"/1000000";}
            public InputStream getInputStream(){if(getRequestProperty("Range").equals("bytes=0-0"))return new ByteArrayInputStream(new byte[1]);return new InputStream(){public int read()throws IOException{reading.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}return -1;}};}
        };};
        RemoteMediaFile source=new RemoteMediaFile(url,stalled,"","",text->{});source.prepare();
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            java.util.concurrent.Future<Boolean> read=worker.submit(()->{try{source.readAt(400000,new byte[16],0,16);return false;}catch(IOException expected){return true;}});
            check(reading.await(2,java.util.concurrent.TimeUnit.SECONDS),"Exercise cancellation during an active video response");
            long start=System.nanoTime();source.close();check(System.nanoTime()-start<250_000_000L,"Closing a stalled video does not wait for socket disconnect or decoder reads");
            release.countDown();check(read.get(2,java.util.concurrent.TimeUnit.SECONDS),"An in-flight read terminates after video cancellation");
            int before=opens.get();try{source.readAt(0,new byte[1],0,1);throw new AssertionError("Read after close");}catch(IOException expected){}
            check(opens.get()==before,"A closed stream cannot open another camera connection");
        }finally{release.countDown();source.close();worker.shutdownNow();}
    }

}
