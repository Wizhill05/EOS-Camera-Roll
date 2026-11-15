package dev.aryan.cameralinklab;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LightroomProvider extends ContentProvider {
    static final String PACKAGE="com.adobe.lrmobile";
    private static final Map<String,Integer> openCounts=new ConcurrentHashMap<>();
    private static final Handler main=new Handler(Looper.getMainLooper());
    @Override public boolean onCreate(){return true;}
    static Uri stage(Context context,File original,MediaItem item)throws IOException {
        File root=new File(context.getFilesDir(),"lightroom-handoff");File dir=new File(root,UUID.randomUUID().toString());if(!dir.mkdirs())throw new IOException("Cannot create Lightroom handoff");
        File target=new File(dir,item.name.replace('/','_').replace('\\','_'));
        try{android.system.Os.link(original.getAbsolutePath(),target.getAbsolutePath());if(!target.isFile()||target.length()!=original.length())throw new IOException("Hard link unavailable");}
        catch(Exception unsupported){try(InputStream in=new FileInputStream(original);OutputStream out=new FileOutputStream(target)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}}
        if(!target.isFile()||target.length()!=original.length())throw new IOException("Incomplete Lightroom handoff");
        target.setLastModified(System.currentTimeMillis());
        // The handoff now owns a hard link or copy. Drop only the app's disposable preview-cache entry.
        new File(original.getParentFile(),"ready").delete();original.delete();
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".lightroom").appendPath(dir.getName()).appendPath(target.getName()).build();
    }
    static boolean installed(Context context){try{context.getPackageManager().getPackageInfo(PACKAGE,0);return true;}catch(PackageManager.NameNotFoundException e){return false;}}
    static void launch(Context context,Uri uri,String mime){
        Intent intent=new Intent(Intent.ACTION_SEND).setPackage(PACKAGE).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(context.getContentResolver(),"Original photo",uri));
        context.startActivity(intent);
    }
    private File file(Uri uri)throws FileNotFoundException {
        List<String> parts=uri.getPathSegments();
        if(parts.size()!=2||!parts.get(0).matches("[0-9a-f-]{36}"))throw new FileNotFoundException("Invalid handoff");
        try{File root=new File(getContext().getFilesDir(),"lightroom-handoff").getCanonicalFile();File f=new File(new File(root,parts.get(0)),parts.get(1)).getCanonicalFile();
            if(!f.getPath().startsWith(root.getPath()+File.separator)||!f.isFile())throw new FileNotFoundException("Handoff expired");return f;
        }catch(IOException e){throw new FileNotFoundException("Handoff unavailable");}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Original is read-only");File f=file(uri);String key=f.getAbsolutePath();
        f.setLastModified(System.currentTimeMillis());openCounts.merge(key,1,Integer::sum);
        try{return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY,main,error->{
            openCounts.compute(key,(k,count)->count==null||count<=1?null:count-1);long closed=System.currentTimeMillis();f.setLastModified(closed);
            main.postDelayed(()->{if(!openCounts.containsKey(key)&&f.lastModified()==closed){f.delete();f.getParentFile().delete();}},60000);
        });}catch(IOException e){openCounts.compute(key,(k,count)->count==null||count<=1?null:count-1);throw new FileNotFoundException("Cannot open staged original");}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]=file.getName();else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=file.length();}cursor.addRow(row);return cursor;}
        catch(FileNotFoundException e){return null;}
    }
    @Override public String getType(Uri uri){String name=uri.getLastPathSegment();if(name==null)return "image/*";name=name.toLowerCase(Locale.ROOT);return name.endsWith(".cr3")?"image/x-canon-cr3":name.endsWith(".cr2")?"image/x-canon-cr2":name.endsWith(".png")?"image/png":name.endsWith(".hif")||name.endsWith(".heif")?"image/heif":"image/jpeg";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String s,String[] args){throw new UnsupportedOperationException();}
    static void cleanup(Context context){File[] dirs=new File(context.getFilesDir(),"lightroom-handoff").listFiles();if(dirs==null)return;long cutoff=System.currentTimeMillis()-86400000L;for(File dir:dirs){File[] files=dir.listFiles();if(files==null)continue;for(File f:files)if(f.lastModified()<cutoff&&!openCounts.containsKey(f.getAbsolutePath()))f.delete();dir.delete();}}
}
