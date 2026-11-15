package dev.aryan.cameralinklab;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryWorkflowTest {
    private ActivityController<GalleryActivity> controller;
    private GalleryActivity activity;
    private TestCameraServer camera;
    @Before public void open(){controller=Robolectric.buildActivity(GalleryActivity.class).setup().visible();activity=controller.get();}
    @After public void close(){controller.pause().stop().destroy();if(camera!=null)try{camera.close();}catch(IOException ignored){}TransferService.busy=false;}
    private Object get(String name)throws Exception{Field f=GalleryActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void set(String name,Object value)throws Exception{Field f=GalleryActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private void call(String name)throws Exception{Method m=GalleryActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private List<MediaItem> seed()throws Exception{
        CameraSession session=new CameraSession(new URL("http://127.0.0.1:1/ccapi"),null,"","",s->{});session.identity="serial:test-r50v";set("session",session);
        List<MediaItem> items=(List<MediaItem>)get("all");items.add(new MediaItem(new URL("http://127.0.0.1:1/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_1001.CR3")));items.add(new MediaItem(new URL("http://127.0.0.1:1/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_1002.JPG")));items.add(new MediaItem(new URL("http://127.0.0.1:1/ccapi/ver140/contents/card1/XFVC/REEL_0011/A_0011.MP4")));call("updateFolders");call("applyFilters");return items;
    }
    @Test public void startsInDarkModeWithEmptyGallery()throws Exception{assertTrue((boolean)get("dark"));assertEquals("EOS Camera Roll",findText(activity.getWindow().getDecorView(),"EOS Camera Roll").getText());assertEquals(0,((RecyclerView)get("grid")).getAdapter().getItemCount());screenshot("empty-dark");}
    @Test public void ratingsFilterAndSavedReceiptsSurviveStoreRecreation()throws Exception{
        List<MediaItem> items=seed();CameraSession session=(CameraSession)get("session");MediaStateStore state=new MediaStateStore(activity);state.rate(session.key(items.get(0)),5);state.rate(session.key(items.get(1)),3);state.saved(session.key(items.get(0)),Uri.parse("content://documents/1"),Uri.parse("content://tree/1"),99,"digest");
        MediaStateStore reloaded=new MediaStateStore(activity);assertEquals(5,reloaded.rating(session.key(items.get(0))));assertTrue(reloaded.saved(session.key(items.get(0))));assertFalse(reloaded.saved(session.key(items.get(1))));
        set("minimumRating",4);call("applyFilters");assertEquals(1,((List<?>)get("visible")).size());assertEquals(items.get(0),((List<?>)get("visible")).get(0));
        set("minimumRating",0);set("filter",2);call("applyFilters");assertEquals(Collections.singletonList(items.get(2)),get("visible"));
        set("filter",0);call("applyFilters");screenshot("library-dark");
    }
    @Test public void lightroomReceivesOriginalWithPrivateReadGrant()throws Exception{
        File dir=new File(activity.getCacheDir(),"handoff-test");dir.mkdirs();File original=new File(dir,"original");byte[] bytes={1,3,4,9,22};try(FileOutputStream out=new FileOutputStream(original)){out.write(bytes);}
        MediaItem item=new MediaItem(new URL("http://camera/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_1001.CR3"));Uri uri=LightroomProvider.stage(activity,original,item);assertFalse(original.exists());
        LightroomProvider provider=Robolectric.buildContentProvider(LightroomProvider.class).create().get();try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri,"r"))){assertArrayEquals(bytes,in.readAllBytes());}
        assertEquals("image/x-canon-cr3",provider.getType(uri));
        try{provider.openFile(uri,"w");fail("Writable original");}catch(FileNotFoundException expected){}
        Uri bad=Uri.parse("content://"+activity.getPackageName()+".lightroom/../../secret");try{provider.openFile(bad,"r");fail("Traversal accepted");}catch(FileNotFoundException expected){}
    }
    @Test public void savedMarkAppearsInActualCell()throws Exception{List<MediaItem> items=seed();CameraSession session=(CameraSession)get("session");new MediaStateStore(activity).saved(session.key(items.get(0)),Uri.parse("content://documents/1"),null,42,"hash");call("applyFilters");RecyclerView grid=(RecyclerView)get("grid");int index=((List<MediaItem>)get("visible")).indexOf(items.get(0));RecyclerView.Adapter adapter=grid.getAdapter();RecyclerView.ViewHolder holder=adapter.createViewHolder(grid,0);adapter.bindViewHolder(holder,index);assertNotNull(findText(holder.itemView,"✓"));}
    @Test public void originalJpegReplacesPreviewAndViewerSupportsCulling()throws Exception{
        File sample=new File("../build/raw-check/R50V-render.jpg");
        Bitmap bitmap=sample.isFile()?BitmapFactory.decodeFile(sample.getPath()):Bitmap.createBitmap(3200,2400,Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,85,bytes);bitmap.recycle();camera=new TestCameraServer(bytes.toByteArray());
        CameraSession session=new CameraSession(new URL(camera.origin()+"/ccapi"),null,"","",s->{});session.identity="serial:jpeg-test";set("session",session);set("images",new GalleryImages(activity,session,()->{}));
        MediaItem photo=new MediaItem(new URL(camera.origin()+"/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_1001.JPG"));((List<MediaItem>)get("all")).add(photo);call("updateFolders");call("applyFilters");set("viewingItems",Collections.singletonList(photo));
        Method open=GalleryActivity.class.getDeclaredMethod("openViewer",int.class);open.setAccessible(true);open.invoke(activity,0);
        long end=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<end&&!((TextView)get("playbackText")).getText().toString().startsWith("Full resolution")){Thread.sleep(20);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();}
        assertTrue(((TextView)get("playbackText")).getText().toString(),((TextView)get("playbackText")).getText().toString().startsWith("Full resolution"));
        List<Button> stars=(List<Button>)get("ratingButtons");stars.get(4).performClick();assertEquals(5,new MediaStateStore(activity).rating(session.key(photo)));assertEquals("★",stars.get(4).getText());
        screenshot("viewer-dark");call("closeViewer");call("applyFilters");screenshot("library-with-photo-dark");RecyclerView gallery=(RecyclerView)get("grid");waitFor(()->{RecyclerView.ViewHolder holder=gallery.findViewHolderForAdapterPosition(0);if(holder==null)return false;ImageView image=(ImageView)((ViewGroup)holder.itemView).getChildAt(0);return image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable&&((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap()!=null;});screenshot("library-with-photo-dark");
    }
    @Test public void cachedLibraryRestoresAndUnreachableRefreshKeepsPhotos()throws Exception{
        List<MediaItem> items=seed();CameraSession session=(CameraSession)get("session");new LibrarySnapshot(session.root,session.identity,items,System.currentTimeMillis()).write(activity);activity.getSharedPreferences("gallery",0).edit().putString("url",session.root.toString()).commit();
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(GalleryActivity.class).setup().visible();activity=controller.get();waitFor(()->!((List<?>)get("all")).isEmpty());assertEquals(3,((List<?>)get("all")).size());assertFalse((boolean)get("cameraVerified"));assertTrue(((TextView)get("subtitle")).getText().toString().contains("Cached"));
        call("refreshLibrary");waitFor(()->!(boolean)get("scanning"));assertEquals(3,((List<?>)get("all")).size());assertFalse((boolean)get("cameraVerified"));assertEquals("Camera out of reach",((LibraryIssue)get("issue")).title);assertEquals(View.GONE,((View)get("emptyPanel")).getVisibility());screenshot("cached-camera-unreachable");
    }
    @Test public void connectedEmptyCardHasDifferentStateFromUnreachableCamera()throws Exception{
        camera=new TestCameraServer(new byte[0]);camera.emptyCatalog=true;CameraSession session=new CameraSession(new URL(camera.origin()+"/ccapi"),null,"","",s->{});set("session",session);set("images",new GalleryImages(activity,session,()->{}));call("scan");waitFor(()->!(boolean)get("scanning"));assertTrue((boolean)get("cameraVerified"));assertNull(get("issue"));assertEquals("No photos or videos",((TextView)get("empty")).getText().toString());screenshot("connected-empty-card");
    }
    @Test public void portraitThumbnailUsesOriginalExifAndSurvivesOfflineRestart()throws Exception{
        Bitmap source=Bitmap.createBitmap(80,50,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(source);Paint paint=new Paint();paint.setColor(Color.RED);canvas.drawRect(0,0,40,50,paint);paint.setColor(Color.BLUE);canvas.drawRect(40,0,80,50,paint);ByteArrayOutputStream encoded=new ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.JPEG,100,encoded);source.recycle();byte[] jpeg=encoded.toByteArray();byte[] uprightExif={ (byte)255,(byte)225,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};ByteArrayOutputStream original=new ByteArrayOutputStream();original.write(jpeg,0,2);original.write(uprightExif);original.write(jpeg,2,jpeg.length-2);
        camera=new TestCameraServer(original.toByteArray());camera.thumbnail=jpeg;CameraSession session=new CameraSession(new URL(camera.origin()+"/ccapi"),null,"","",s->{});MediaItem item=new MediaItem(new URL(camera.origin()+"/ccapi/ver140/contents/card1/DCIM/100CANON/PORTRAIT.JPG"));GalleryImages loader=new GalleryImages(activity,session,()->{});ImageView first=new ImageView(activity);loader.thumbnail(item,first);waitFor(()->first.getDrawable()!=null);Bitmap image=((android.graphics.drawable.BitmapDrawable)first.getDrawable()).getBitmap();assertEquals(50,image.getWidth());assertEquals(80,image.getHeight());assertTrue(Color.red(image.getPixel(25,10))>Color.blue(image.getPixel(25,10)));loader.close();int requests=camera.requests.get();
        GalleryImages offline=new GalleryImages(activity,session,()->{});offline.networkEnabled=false;ImageView second=new ImageView(activity);offline.thumbnail(item,second);waitFor(()->second.getDrawable()!=null);Bitmap persisted=((android.graphics.drawable.BitmapDrawable)second.getDrawable()).getBitmap();assertEquals(50,persisted.getWidth());assertEquals(80,persisted.getHeight());assertEquals(requests,camera.requests.get());offline.close();session.close();
    }
    @Test public void masonryGalleryKeepsPhotoProportionsInDarkAndLight()throws Exception{
        CameraSession session=new CameraSession(new URL("http://127.0.0.1:1/ccapi"),null,"","",message->{});session.identity="serial:masonry-test";set("session",session);GalleryImages images=new GalleryImages(activity,session,()->((RecyclerView)uncheckedGet("grid")).getAdapter().notifyDataSetChanged());images.networkEnabled=false;set("images",images);set("cameraVerified",true);set("libraryStatus","Camera connected");
        File sample=new File("../build/raw-check/R50V-render.jpg");Bitmap source=sample.isFile()?BitmapFactory.decodeFile(sample.getPath()):Bitmap.createBitmap(1000,750,Bitmap.Config.ARGB_8888);ThumbnailStore disk=new ThumbnailStore(activity);List<MediaItem> items=(List<MediaItem>)get("all");int[][] sizes={{600,400},{400,600},{600,400},{600,420},{420,600},{600,440},{400,600},{600,400}};
        for(int n=0;n<sizes.length;n++){MediaItem item=new MediaItem(new URL("http://127.0.0.1:1/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_"+(1010-n)+".JPG"));items.add(item);Bitmap thumb=Bitmap.createBitmap(sizes[n][0],sizes[n][1],Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(thumb);int inset=(n%4)*source.getWidth()/12;int available=source.getWidth()-2*inset;float ratio=sizes[n][0]/(float)sizes[n][1];int cropWidth=Math.min(available,(int)(source.getHeight()*ratio)),cropHeight=Math.min(source.getHeight(),(int)(available/ratio));int x=(source.getWidth()-cropWidth)/2,y=(source.getHeight()-cropHeight)/2;Rect region=new Rect(x,y,x+cropWidth,y+cropHeight);canvas.drawBitmap(source,region,new Rect(0,0,thumb.getWidth(),thumb.getHeight()),new Paint(3));disk.write(session.key(item),thumb);thumb.recycle();}source.recycle();new MediaStateStore(activity).rate(session.key(items.get(1)),5);new MediaStateStore(activity).saved(session.key(items.get(2)),Uri.parse("content://documents/test"),null,99,"hash");call("updateFolders");call("applyFilters");screenshot("gallery-masonry-dark");waitFor(()->loadedVisibleThumbnails()>=4);screenshot("gallery-masonry-dark");RecyclerView grid=(RecyclerView)get("grid");assertEquals(2,((androidx.recyclerview.widget.StaggeredGridLayoutManager)grid.getLayoutManager()).getSpanCount());View portrait=grid.findViewHolderForAdapterPosition(1).itemView;assertTrue(portrait.getHeight()>portrait.getWidth());
        new LibrarySnapshot(session.root,session.identity,items,System.currentTimeMillis()).write(activity);activity.getSharedPreferences("gallery",0).edit().putString("appearance","Light").putString("url",session.root.toString()).commit();controller.pause().stop().destroy();controller=Robolectric.buildActivity(GalleryActivity.class).setup().visible();activity=controller.get();waitFor(()->((List<?>)get("all")).size()==8);screenshot("gallery-masonry-light");waitFor(()->loadedVisibleThumbnails()>=4);assertFalse((boolean)get("dark"));screenshot("gallery-masonry-light");
    }
    private Object uncheckedGet(String name){try{return get(name);}catch(Exception e){throw new RuntimeException(e);}}
    private int loadedVisibleThumbnails()throws Exception{RecyclerView grid=(RecyclerView)get("grid");int count=0;for(int i=0;i<grid.getChildCount();i++){View child=grid.getChildAt(i);if(child instanceof ViewGroup&&((ViewGroup)child).getChildAt(0) instanceof ImageView){ImageView image=(ImageView)((ViewGroup)child).getChildAt(0);if(image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable&&((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap()!=null)count++;}}return count;}
    @Test public void snapshotRejectsForeignOriginPaths()throws Exception{
        String json="{\"camera\":\"http://camera/ccapi\",\"identity\":\"camera\",\"paths\":[\"https://other.example/ccapi/ver140/contents/card1/private.JPG\"]}";try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),"library-v1.json"))){out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}try{LibrarySnapshot.read(activity);fail("Foreign cached URL accepted");}catch(IOException expected){}
    }
    private interface Condition{boolean ready()throws Exception;}
    private void waitFor(Condition condition)throws Exception{long end=System.currentTimeMillis()+10000;while(!condition.ready()&&System.currentTimeMillis()<end){Thread.sleep(15);Shadows.shadowOf(Looper.getMainLooper()).idle();}assertTrue("Asynchronous operation did not finish",condition.ready());}
    private TextView findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void screenshot(String name)throws Exception{View root=activity.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1179,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2553,View.MeasureSpec.EXACTLY));root.layout(0,0,1179,2553);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();Bitmap bitmap=Bitmap.createBitmap(1179,2553,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));File dir=new File("build/ui-checks");dir.mkdirs();try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
}
