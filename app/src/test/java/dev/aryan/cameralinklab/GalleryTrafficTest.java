package dev.aryan.cameralinklab;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.widget.ImageView;
import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryTrafficTest {
    private Context context;private TestCameraServer server;private CameraSession session;private GalleryImages images;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();Bitmap bitmap=Bitmap.createBitmap(80,50,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);bitmap.recycle();
        server=new TestCameraServer(new byte[2*1024*1024]);server.thumbnail=out.toByteArray();server.rotation=90;session=new CameraSession(new URL(server.origin()+"/ccapi"),null,"","",text->{});images=new GalleryImages(context,session,()->{});
    }
    @After public void cleanup()throws Exception{images.close();session.close();server.close();}
    private MediaItem item(int number)throws Exception{return new MediaItem(new URL(server.origin()+"/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_"+number+".JPG"));}
    private interface Condition{boolean ready()throws Exception;}
    private void waitFor(Condition condition)throws Exception{long end=System.currentTimeMillis()+10000;while(!condition.ready()&&System.currentTimeMillis()<end){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}assertTrue("Image work did not finish",condition.ready());}
    private boolean loaded(ImageView view){return view.getDrawable() instanceof BitmapDrawable&&((BitmapDrawable)view.getDrawable()).getBitmap()!=null;}
    private void idle()throws Exception{Field field=GalleryImages.class.getDeclaredField("thumbs");field.setAccessible(true);ThreadPoolExecutor worker=(ThreadPoolExecutor)field.get(images);waitFor(()->worker.getActiveCount()==0&&worker.getQueue().isEmpty());Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void galleryNeverRequestsOriginalFiles()throws Exception{
        server.rotation=-1;server.thumbnailGate=new java.util.concurrent.CountDownLatch(1);
        List<ImageView> views=new ArrayList<>();for(int n=0;n<4;n++){ImageView view=new ImageView(context);views.add(view);images.thumbnail(item(n),view);}server.thumbnailGate.countDown();waitFor(()->views.stream().allMatch(this::loaded));idle();
        assertEquals("Browsing must never probe originals, even when Range is ignored",0,server.targets.stream().filter(path->!path.contains("?")).count());
        assertEquals(4,server.targets.stream().filter(path->path.contains("kind=thumbnail")).count());
        assertEquals(1,server.maximumActive.get());
        assertEquals("Do not keep probing a camera format that omits rotation",1,server.targets.stream().filter(path->path.contains("kind=info")).count());
        assertTrue("Thumbnail jobs must run before optional metadata",server.targets.subList(0,4).stream().allMatch(path->path.contains("kind=thumbnail")));
        assertTrue(server.targets.toString(),server.targets.stream().allMatch(path->path.contains("kind=thumbnail")||path.contains("kind=info")));
    }
    @Test public void cancellingPhotoPreviewPreventsFollowupOriginalDownload()throws Exception{
        server.displayGate=new java.util.concurrent.CountDownLatch(1);images.preview(item(0),bitmap->{},note->{},(done,total)->{});
        try{waitFor(()->server.targets.stream().anyMatch(path->path.contains("kind=display")));images.cancelPreview();server.displayGate.countDown();
            Field executor=GalleryImages.class.getDeclaredField("previews");executor.setAccessible(true);((java.util.concurrent.ExecutorService)executor.get(images)).submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("A cancelled preview must not start an original or metadata request",1,server.requests.get());
            ImageView next=new ImageView(context);images.thumbnail(item(1),next);waitFor(()->loaded(next));
        }finally{server.displayGate.countDown();}
    }
    @Test public void videoThumbnailsNeverProbeOriginalsOrRanges()throws Exception{
        server.poisonOnRange=true;ImageView view=new ImageView(context);MediaItem video=new MediaItem(new URL(server.origin()+"/ccapi/ver140/contents/card1/XFVC/CLIP.MP4"));images.thumbnail(video,view);waitFor(()->loaded(view));idle();assertEquals(Collections.singletonList(video.url.getPath()+"?kind=thumbnail"),server.targets);assertTrue(server.ranges.isEmpty());assertFalse(server.poisoned);
    }
    @Test public void openingViewerLetsTheSmallThumbnailFinishBeforeAnotherRequest()throws Exception{
        images.close();session.close();List<String> log=new java.util.concurrent.CopyOnWriteArrayList<>();session=new CameraSession(new URL(server.origin()+"/ccapi"),null,"","",log::add);images=new GalleryImages(context,session,()->{});server.thumbnailGate=new java.util.concurrent.CountDownLatch(1);images.thumbnail(item(0),new ImageView(context));waitFor(()->server.requests.get()==1);images.setPaused(true);
        java.util.concurrent.ExecutorService api=java.util.concurrent.Executors.newSingleThreadExecutor();try{java.util.concurrent.Future<?> info=api.submit(()->{try{session.json(MediaCatalog.query(item(0).url,"kind=info"));}catch(Exception error){throw new RuntimeException(error);}});
            try{info.get(150,java.util.concurrent.TimeUnit.MILLISECONDS);fail("Another request overtook the small thumbnail");}catch(java.util.concurrent.TimeoutException expected){assertEquals(1,server.requests.get());}
            server.thumbnailGate.countDown();info.get(3,java.util.concurrent.TimeUnit.SECONDS);idle();assertTrue("Finish the response instead of aborting on viewer entry: "+log,log.stream().anyMatch(line->line.startsWith("HTTP: closed; "+server.thumbnail.length+" bytes;")));
        }finally{server.thumbnailGate.countDown();api.shutdownNow();}
    }
    @Test public void firstCameraFailureStopsTheThumbnailQueueUntilRefresh()throws Exception{
        server.failThumbnails=true;List<ImageView> views=new ArrayList<>();for(int i=0;i<10;i++){ImageView view=new ImageView(context);views.add(view);images.thumbnail(item(i),view);}idle();assertEquals("Do not issue the rest of the queued requests to a failing camera",1,server.requests.get());assertFalse(images.networkEnabled);
        server.failThumbnails=false;images.refresh();ImageView retried=new ImageView(context);images.thumbnail(item(0),retried);waitFor(()->loaded(retried));idle();assertTrue(images.networkEnabled);
    }
    @Test public void refreshAndRebindReuseCacheWithoutCameraRequests()throws Exception{
        MediaItem item=item(0);ImageView first=new ImageView(context);images.thumbnail(item,first);waitFor(()->loaded(first));idle();int initial=server.requests.get();images.refresh();ImageView rebound=new ImageView(context);images.thumbnail(item,rebound);waitFor(()->loaded(rebound));idle();assertEquals("A cached thumbnail must not trigger metadata revalidation",initial,server.requests.get());
        images.close();images=new GalleryImages(context,session,()->{});ImageView restarted=new ImageView(context);images.thumbnail(item,restarted);waitFor(()->loaded(restarted));idle();assertEquals("The persisted thumbnail must work without another request",initial,server.requests.get());
    }    @Test public void queuedRecycledTilesNeverReachCamera()throws Exception{
        server.thumbnailGate=new java.util.concurrent.CountDownLatch(1);ImageView active=new ImageView(context);images.thumbnail(item(0),active);waitFor(()->server.requests.get()==1);ImageView recycled=new ImageView(context);images.thumbnail(item(1),recycled);images.recycle(recycled);server.thumbnailGate.countDown();waitFor(()->loaded(active));idle();assertFalse(server.targets.toString(),server.targets.stream().anyMatch(path->path.contains("IMG_1.JPG")));
    }
    @Test public void thumbnailIsVisibleBeforeSlowOrientationMetadataFinishes()throws Exception{
        server.infoGate=new java.util.concurrent.CountDownLatch(1);ImageView view=new ImageView(context);images.thumbnail(item(0),view);waitFor(()->loaded(view));assertEquals("Optional rotation must not block displaying the JPEG",50,((BitmapDrawable)view.getDrawable()).getBitmap().getHeight());server.infoGate.countDown();idle();assertEquals(80,((BitmapDrawable)view.getDrawable()).getBitmap().getHeight());assertTrue(server.targets.stream().noneMatch(path->!path.contains("?")));
    }
    @Test public void openingPhotoLearnsMissingRotationWithoutGalleryOriginalProbes()throws Exception{
        images.close();session.close();server.close();Bitmap bitmap=Bitmap.createBitmap(80,50,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);ByteArrayOutputStream jpeg=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,95,jpeg);bitmap.recycle();byte[] image=jpeg.toByteArray();byte[] exif={(byte)255,(byte)225,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};ByteArrayOutputStream original=new ByteArrayOutputStream();original.write(image,0,2);original.write(exif);original.write(image,2,image.length-2);server=new TestCameraServer(original.toByteArray());server.thumbnail=image;session=new CameraSession(new URL(server.origin()+"/ccapi"),null,"","",text->{});images=new GalleryImages(context,session,()->{});ImageView tile=new ImageView(context);MediaItem item=item(0);images.thumbnail(item,tile);waitFor(()->loaded(tile));idle();assertEquals(0,server.targets.stream().filter(path->!path.contains("?")).count());
        java.util.concurrent.atomic.AtomicBoolean full=new java.util.concurrent.atomic.AtomicBoolean();images.setPaused(true);images.preview(item,value->{},note->{if(note.startsWith("Full resolution"))full.set(true);},(done,total)->{});waitFor(full::get);assertTrue(server.targets.stream().anyMatch(path->!path.contains("?")));int requests=server.requests.get();images.setPaused(false);ImageView corrected=new ImageView(context);images.thumbnail(item,corrected);waitFor(()->loaded(corrected));assertEquals(80,((BitmapDrawable)corrected.getDrawable()).getBitmap().getHeight());assertEquals(requests,server.requests.get());
    }
    @Test public void pauseAndResumeDoNotLoseTheActiveThumbnail()throws Exception{
        server.thumbnailGate=new java.util.concurrent.CountDownLatch(1);MediaItem photo=item(0);ImageView old=new ImageView(context);images.thumbnail(photo,old);waitFor(()->server.requests.get()==1);images.setPaused(true);images.setPaused(false);ImageView resumed=new ImageView(context);images.thumbnail(photo,resumed);server.thumbnailGate.countDown();waitFor(()->loaded(resumed));idle();assertTrue(server.targets.stream().noneMatch(path->!path.contains("?")));
    }
    @Test public void rapidBindingsKeepTheQueueBounded()throws Exception{
        server.thumbnailGate=new java.util.concurrent.CountDownLatch(1);List<ImageView> views=new ArrayList<>();ImageView first=new ImageView(context);views.add(first);images.thumbnail(item(0),first);waitFor(()->server.requests.get()==1);for(int n=1;n<40;n++){ImageView view=new ImageView(context);views.add(view);images.thumbnail(item(n),view);}Field field=GalleryImages.class.getDeclaredField("thumbs");field.setAccessible(true);ThreadPoolExecutor worker=(ThreadPoolExecutor)field.get(images);assertTrue(worker.getQueue().size()<=16);assertEquals(1,server.requests.get());images.setPaused(true);server.thumbnailGate.countDown();idle();assertEquals(1,server.requests.get());
    }
    @Test public void reboundTileReceivesAnAlreadyQueuedThumbnail()throws Exception{
        server.thumbnailGate=new java.util.concurrent.CountDownLatch(1);ImageView blocker=new ImageView(context);images.thumbnail(item(0),blocker);waitFor(()->server.requests.get()==1);MediaItem photo=item(1);ImageView recycled=new ImageView(context);images.thumbnail(photo,recycled);images.recycle(recycled);ImageView replacement=new ImageView(context);images.thumbnail(photo,replacement);server.thumbnailGate.countDown();waitFor(()->loaded(replacement));idle();assertEquals(1,server.targets.stream().filter(path->path.contains("IMG_1.JPG?kind=thumbnail")).count());assertNull(recycled.getDrawable());
    }
    @Test public void recycledBurstCannotCrowdOutTheNewVisibleTile()throws Exception{
        server.thumbnailGate=new java.util.concurrent.CountDownLatch(1);ImageView active=new ImageView(context);images.thumbnail(item(0),active);waitFor(()->server.requests.get()==1);List<ImageView> old=new ArrayList<>();for(int n=1;n<=16;n++){ImageView view=new ImageView(context);old.add(view);images.thumbnail(item(n),view);}for(ImageView view:old)images.recycle(view);ImageView visible=new ImageView(context);images.thumbnail(item(17),visible);server.thumbnailGate.countDown();waitFor(()->loaded(visible));idle();assertTrue(server.targets.stream().anyMatch(path->path.contains("IMG_17.JPG?kind=thumbnail")));assertFalse(server.targets.stream().anyMatch(path->path.contains("IMG_1.JPG")));
    }

}
