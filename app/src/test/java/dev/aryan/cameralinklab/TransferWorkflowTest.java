package dev.aryan.cameralinklab;

import android.app.*;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ServiceController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class TransferWorkflowTest {
    private Context context;private TestCameraServer server;private CameraSession source;private ServiceController<TransferService> service;
    private static final byte[] ORIGINAL={3,1,4,1,5,9,2,6};
    private static final Uri TREE=Uri.parse("content://test.camera.documents/tree/root");
    private MediaItem item;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();TransferService.busy=false;
        android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="test.camera.documents";info.exported=true;info.grantUriPermissions=true;info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission="android.permission.MANAGE_DOCUMENTS";Robolectric.buildContentProvider(TestDocuments.class).create(info);
        server=new TestCameraServer(ORIGINAL);String origin=server.origin();source=new CameraSession(new URL(origin+"/ccapi"),null,"","",s->{});source.identity="serial:transfer-camera";
        item=new MediaItem(new URL(origin+"/ccapi/ver140/contents/card1/DCIM/100CANON/IMG_0001.JPG"));
    }
    @After public void cleanup()throws Exception{if(service!=null)service.destroy();if(source!=null)source.close();if(server!=null)server.close();TransferService.busy=false;}
    private void run(boolean newFolder)throws Exception{
        TransferService.start(context,source,Collections.singletonList(item),TREE,newFolder,false);
        service=Robolectric.buildService(TransferService.class).create().startCommand(0,1);
        Field field=TransferService.class.getDeclaredField("worker");field.setAccessible(true);Thread worker=(Thread)field.get(service.get());assertNotNull(worker);worker.join(15000);assertFalse("Transfer did not finish",worker.isAlive());
    }
    @Test public void savesOriginalAndReceiptThenPostsCompletion()throws Exception{
        run(false);MediaStateStore state=new MediaStateStore(context);assertTrue(state.saved(source.key(item)));assertTrue(state.savedTo(source.key(item),TREE));
        try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(state.savedUri(source.key(item))))){assertArrayEquals(ORIGINAL,in.readAllBytes());}
        assertTrue(TransferService.summary.startsWith("Saved 1 original"));assertNotNull(Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getNotification(72));
    }
    @Test public void copyCardCreatesNewDirectory()throws Exception{run(true);assertEquals(1,TestDocuments.directories);assertTrue(new MediaStateStore(context).saved(source.key(item)));}
    @Test public void failedWriteNeverGetsSavedMark()throws Exception{TestDocuments.rejectWrites=true;run(false);MediaStateStore state=new MediaStateStore(context);assertFalse(state.saved(source.key(item)));assertTrue(state.failedBefore(source.key(item)));assertTrue(TransferService.summary.startsWith("Transfer stopped"));assertEquals(0,TestDocuments.files.size());assertNotNull(Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getNotification(72));}
    @Test public void repeatedSaveReusesExistingReceipt()throws Exception{
        run(false);String receipt=new MediaStateStore(context).savedUri(source.key(item));assertTrue(new MediaStateStore(context).savedTo(source.key(item),TREE));try(Cursor check=context.getContentResolver().query(Uri.parse(receipt),new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},Bundle.EMPTY,null)){assertNotNull(check);assertTrue(check.moveToFirst());}service.destroy();service=null;TransferService.busy=false;
        run(false);assertEquals(receipt,new MediaStateStore(context).savedUri(source.key(item)));assertEquals(1,TestDocuments.files.size());assertTrue(TransferService.summary.contains("1 already saved"));
    }
    public static final class TestDocuments extends DocumentsProvider {
        static final Map<String,File> files=new HashMap<>();static int directories;static boolean rejectWrites;
        @Override public boolean onCreate(){files.clear();directories=0;rejectWrites=false;return true;}
        @Override public Cursor queryRoots(String[] projection){return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});}
        @Override public Cursor queryDocument(String id,String[] projection){String[] columns=projection==null?new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE}:projection;MatrixCursor cursor=new MatrixCursor(columns);if(!id.equals("root")&&!files.containsKey(id))return cursor;Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++){switch(columns[i]){case DocumentsContract.Document.COLUMN_DOCUMENT_ID:row[i]=id;break;case DocumentsContract.Document.COLUMN_DISPLAY_NAME:row[i]=id;break;case DocumentsContract.Document.COLUMN_MIME_TYPE:row[i]=DocumentsContract.Document.MIME_TYPE_DIR;break;}}cursor.addRow(row);return cursor;}
        @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort){return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID});}
        @Override public boolean isChildDocument(String parent,String child){return true;}
        @Override public String createDocument(String parent,String mime,String name)throws FileNotFoundException{String id=UUID.randomUUID().toString();File file=new File(getContext().getFilesDir(),"test-document-"+id);if(mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)){directories++;file.mkdirs();}files.put(id,file);return id;}
        @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal cancellation)throws FileNotFoundException{if(rejectWrites&&mode.contains("w"))throw new FileNotFoundException("Test folder is read-only");return ParcelFileDescriptor.open(files.get(id),ParcelFileDescriptor.parseMode(mode));}
        @Override public void deleteDocument(String id){File file=files.remove(id);if(file!=null)file.delete();}
    }
}
