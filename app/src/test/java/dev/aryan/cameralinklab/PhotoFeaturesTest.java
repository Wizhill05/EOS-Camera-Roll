package dev.aryan.cameralinklab;

import android.graphics.*;
import android.media.ExifInterface;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoFeaturesTest {
    private ActivityController<GalleryActivity> controller;private GalleryActivity activity;
    @Before public void setup(){controller=Robolectric.buildActivity(GalleryActivity.class).setup().visible();activity=controller.get();}
    @After public void close(){controller.pause().stop().destroy();}
    private Object get(String field)throws Exception{Field f=GalleryActivity.class.getDeclaredField(field);f.setAccessible(true);return f.get(activity);}
    private void set(String field,Object value)throws Exception{Field f=GalleryActivity.class.getDeclaredField(field);f.setAccessible(true);f.set(activity,value);}
    private void call(String name)throws Exception{Method m=GalleryActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private List<MediaItem> seed()throws Exception{
        CameraSession session=new CameraSession(new URL("http://127.0.0.1:1/ccapi"),null,"","",message->{});set("session",session);GalleryImages images=new GalleryImages(activity,session,()->{});images.networkEnabled=false;set("images",images);List<MediaItem> files=(List<MediaItem>)get("all");
        for(String name:new String[]{"IMG_0001.CR3","IMG_0001.JPG","IMG_0002.JPG"})files.add(new MediaItem(new URL("http://127.0.0.1:1/ccapi/ver140/contents/card1/DCIM/100CANON/"+name)));
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inSampleSize=8;Bitmap thumb=BitmapFactory.decodeFile("../build/raw-check/R50V-render.jpg",bounds);if(thumb==null){thumb=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888);thumb.eraseColor(0xffa55d47);}ThumbnailStore disk=new ThumbnailStore(activity);for(MediaItem file:files)disk.write(session.key(file),thumb);thumb.recycle();call("updateFolders");call("applyFilters");return files;
    }
    private TextView text(View view,String value){if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}
    private void render(View root,String name)throws Exception{root.measure(View.MeasureSpec.makeMeasureSpec(1179,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2553,View.MeasureSpec.EXACTLY));root.layout(0,0,1179,2553);Shadows.shadowOf(Looper.getMainLooper()).idle();Bitmap bitmap=Bitmap.createBitmap(1179,2553,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));File dir=new File("build/ui-checks");dir.mkdirs();try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    @Test public void pairUsesJpegRepresentativeAndFormatFilterChoosesOriginal()throws Exception{
        List<MediaItem> files=seed();assertEquals(2,((List<?>)get("visible")).size());assertTrue(((List<?>)get("visible")).contains(files.get(1)));set("formatFilter",1);call("applyFilters");assertEquals(Collections.singletonList(files.get(0)),get("visible"));set("formatFilter",2);call("applyFilters");assertEquals(2,((List<?>)get("visible")).size());
        set("formatFilter",3);call("applyFilters");assertEquals(Collections.singletonList(files.get(1)),get("visible"));set("formatFilter",4);call("applyFilters");assertEquals(Collections.singletonList(files.get(2)),get("visible"));
    }
    @Test public void pairSavedMarkRequiresBothFilesAndIsoFiltersUseCaptureMetadata()throws Exception{
        List<MediaItem> files=seed();CameraSession session=(CameraSession)get("session");MediaStateStore state=new MediaStateStore(activity);state.saved(session.key(files.get(1)),android.net.Uri.parse("content://saved/jpeg"),null,1,"hash");Method saved=GalleryActivity.class.getDeclaredMethod("pairSaved",MediaItem.class);saved.setAccessible(true);assertFalse((boolean)saved.invoke(activity,files.get(1)));state.saved(session.key(files.get(0)),android.net.Uri.parse("content://saved/raw"),null,1,"hash");assertTrue((boolean)saved.invoke(activity,files.get(1)));new CaptureMetadata(activity).camera(session.key(files.get(1)),new JSONObject("{\"iso\":\"400\"}"),activity);state.cameraRating(session.key(files.get(1)),4);set("isoFilter",1);call("applyFilters");assertEquals(Collections.singletonList(files.get(1)),get("visible"));set("formatFilter",1);call("applyFilters");assertEquals(Collections.singletonList(files.get(0)),get("visible"));set("minimumRating",4);call("applyFilters");assertEquals(Collections.singletonList(files.get(0)),get("visible"));
    }
    @Test public void cameraRatingAndPhoneOverrideAreDistinctAndPersistent()throws Exception{
        MediaStateStore state=new MediaStateStore(activity);CaptureMetadata metadata=new CaptureMetadata(activity);metadata.camera("photo",new JSONObject("{\"rating\":\"4\"}"),activity);assertEquals(4,new MediaStateStore(activity).rating("photo"));assertFalse(state.localRating("photo"));state.rate("photo",2);metadata.camera("photo",new JSONObject("{\"rating\":\"4\"}"),activity);assertEquals(2,state.rating("photo"));assertTrue(state.localRating("photo"));metadata.camera("photo",new JSONObject("{\"rating\":\"2\"}"),activity);assertFalse(state.localRating("photo"));assertEquals(2,state.rating("photo"));
    }
    @Test public void captureSettingsComeFromOriginalExif()throws Exception{
        File file=new File(activity.getCacheDir(),"exposure.jpg");Bitmap image=Bitmap.createBitmap(40,30,Bitmap.Config.ARGB_8888);try(OutputStream out=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.JPEG,90,out);}image.recycle();ExifInterface exif=new ExifInterface(file.getPath());exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME,"0.004");exif.setAttribute(ExifInterface.TAG_F_NUMBER,"5.6");exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS,"400");exif.setAttribute(ExifInterface.TAG_MODEL,"EOS R50 V");exif.saveAttributes();CaptureMetadata metadata=new CaptureMetadata(activity);metadata.original("photo",file,activity);assertTrue(metadata.summary("photo"),metadata.summary("photo").contains("1/250"));assertTrue(metadata.summary("photo").contains("f/5.6"));assertTrue(metadata.summary("photo").contains("ISO 400"));assertTrue(metadata.details("photo").contains("EOS R50 V"));
    }
    @Test public void viewerTogglesRawAndJpegAndShowsCaptureSettings()throws Exception{
        List<MediaItem> files=seed();CameraSession session=(CameraSession)get("session");new CaptureMetadata(activity).camera(session.key(files.get(1)),new JSONObject("{\"rating\":\"4\",\"shutterspeed\":\"1/250\",\"aperture\":\"5.6\",\"iso\":\"400\"}"),activity);
        set("viewingItems",Collections.singletonList(files.get(1)));Method open=GalleryActivity.class.getDeclaredMethod("openViewer",int.class);open.setAccessible(true);open.invoke(activity,0);assertEquals(files.get(1),get("viewing"));assertNotNull(text((View)get("viewer"),"JPEG"));assertTrue(((TextView)get("captureSummary")).getText().toString().contains("ISO 400"));render(activity.getWindow().getDecorView(),"paired-viewer-jpeg");text((View)get("viewer"),"RAW").performClick();assertEquals(files.get(0),get("viewing"));assertTrue(text((View)get("viewer"),"RAW").isSelected());assertTrue(((TextView)get("captureSummary")).getText().toString().contains("ISO 400"));render(activity.getWindow().getDecorView(),"paired-viewer-raw");android.graphics.Rect visibleSave=new android.graphics.Rect();assertTrue(((View)get("viewerSave")).getGlobalVisibleRect(visibleSave));assertEquals(((View)get("viewerSave")).getHeight(),visibleSave.height());
    }
    @Test public void lightFilterSheetKeepsChoicesReadable()throws Exception{
        activity.getSharedPreferences("gallery",0).edit().putString("appearance","Light").commit();controller.pause().stop().destroy();controller=Robolectric.buildActivity(GalleryActivity.class).setup().visible();activity=controller.get();seed();call("filterSheet");android.app.Dialog dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();assertFalse((boolean)get("dark"));render(dialog.getWindow().getDecorView(),"filters-light");assertNotNull(text(dialog.getWindow().getDecorView(),"RAW"));dialog.dismiss();
    }
    @Test public void filterSheetHasFormatRatingAndSaveGroups()throws Exception{
        seed();call("filterSheet");android.app.Dialog dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();View root=dialog.getWindow().getDecorView();assertNotNull(text(root,"RAW + JPEG pairs"));assertNotNull(text(root,"Not saved"));assertNotNull(text(root,"Exactly"));render(root,"filters-dark");text(root,"RAW").performClick();assertEquals(1,((List<?>)get("visible")).size());text(root,"Not saved").performClick();assertEquals(1,((List<?>)get("visible")).size());text(root,"At least").performClick();assertEquals(0,((List<?>)get("visible")).size());text(root,"Any rating").performClick();assertEquals(1,((List<?>)get("visible")).size());text(root,"Show results").performClick();assertFalse(dialog.isShowing());
    }
}
