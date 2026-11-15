package dev.aryan.cameralinklab;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class GalleryActivity extends Activity {
    private int PAPER=0xff0b0b0d,SURFACE=0xff19191d,INK=0xfff5f3f4,MUTED=0xffaaa5aa,ACCENT=0xffff777d,GREEN=0xff85ceab;
    private int navMode;
    private boolean cameraVerified,restoring=true;
    private String libraryStatus="Connect your camera",emptyTitle="Your camera roll starts here",emptyDetail="Connect a camera to browse your photos and videos.";
    private LibraryIssue issue;
    private LinearLayout emptyPanel;
    private TextView emptyDescription;
    private Button emptyAction;
    private final List<LinearLayout> navigation=new ArrayList<>();
    private boolean dark=true,pendingNewFolder;
    private int minimumRating;
    private MediaStateStore states;
    private Button ratingFilter,viewerSave;
    private final List<Button> ratingButtons=new ArrayList<>();
    private ProgressBar previewProgress,viewerTransferProgress;
    private TextView viewerTransferText;
    private long seenCompletion;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService catalogWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService viewerWorker=Executors.newSingleThreadExecutor();
    private final List<MediaItem> all=new ArrayList<>(),visible=new ArrayList<>();
    private final Set<MediaItem> selected=new LinkedHashSet<>();
    private CameraSession session;
    private GalleryImages images;
    private volatile boolean scanCancelled,destroyed;
    private boolean scanning,descending=true,showNames,resumed;
    private volatile int generation,viewerGeneration;
    private int filter;
    private String folder="";
    private LinearLayout page,header,selectionBar,transferBar;
    private FrameLayout root,library,viewer,stage;
    private RecyclerView grid;
    private Cells adapter;
    private Spinner folders;
    private TextView subtitle,empty,countText,selectionText,transferText,viewerTitle,viewerDetail,playbackText;
    private ProgressBar scanProgress,transferProgress;
    private Button saveSelection,cancelTransfer,play;
    private final List<Button> filters=new ArrayList<>();
    private List<MediaItem> viewingItems=Collections.emptyList();
    private MediaItem viewing;
    private int viewingIndex;
    private ZoomPhotoView photo;
    private TextureView texture;
    private MediaPlayer player;
    private volatile RemoteMediaFile remote;
    private boolean prepared,playbackStarting;
    private int resumePosition;
    private volatile int playbackGeneration;
    private SeekBar seek;
    private Surface surface;
    private final Runnable playbackTick=new Runnable(){@Override public void run(){
        if(viewer==null||destroyed)return;
        if(prepared&&player!=null){try{if(!seek.isPressed())seek.setProgress(player.getCurrentPosition());playbackText.setText(time(player.getCurrentPosition())+" / "+time(player.getDuration()));}catch(IllegalStateException ignored){}}
        else if(playbackStarting&&remote!=null&&remote.buffered()>=0)playbackText.setText("Buffering "+TransferService.human(remote.buffered())+" from camera…");
        main.postDelayed(this,500);
    }};
    private List<MediaItem> pendingItems=Collections.emptyList();
    private boolean pendingTree;
    private final Runnable transferObserver=()->{if(!destroyed)updateTransfer();};

    @Override public void onCreate(Bundle state){
        applyAppearance();super.onCreate(state);states=new MediaStateStore(this);seenCompletion=getSharedPreferences("gallery",0).getLong("completion",0);showNames=getSharedPreferences("gallery",0).getBoolean("names",false);
        root=new FrameLayout(this);root.setBackgroundColor(PAPER);setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        styleSystemBars();
        page=column();root.addView(page,new FrameLayout.LayoutParams(-1,-1));buildLibrary();
        TransferService.observers.add(transferObserver);
        updateTransfer();restoreLibrary();log("APP: EOS Camera Roll "+appVersion()+".");
    }
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException impossible){return "development";}}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private TextView label(String text,int size,int color,boolean medium){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(3),1);if(medium)view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return view;}
    private GradientDrawable background(int color,int border,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private Button button(String text,boolean primary,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?0xffffffff:INK);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(5),dp(12),dp(5));b.setStateListAnimator(null);b.setBackground(background(primary?0xffd92b35:SURFACE,0,24));b.setOnClickListener(v->{try{action.run();}catch(Exception e){message(e.getMessage());log("UI: "+e.getMessage());}});return b;}
    private void gap(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private void applyAppearance(){
        String mode=getSharedPreferences("gallery",0).getString("appearance","Dark");dark=mode.equals("Dark")||mode.equals("System")&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        if(!dark){PAPER=0xfffaf9fa;SURFACE=0xffffffff;INK=0xff202025;MUTED=0xff69636a;ACCENT=0xffbc2030;GREEN=0xff276f58;}else{PAPER=0xff0b0b0d;SURFACE=0xff19191d;INK=0xfff5f3f4;MUTED=0xffaaa5aa;ACCENT=0xffff777d;GREEN=0xff85ceab;}
        setTheme(dark?R.style.CameraTheme:R.style.CameraTheme_Light);
    }
    private void styleSystemBars(){getWindow().setStatusBarColor(PAPER);getWindow().setNavigationBarColor(PAPER);getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
    private ImageButton icon(String name,String description,Runnable action){ImageButton view=new ImageButton(this);view.setImageDrawable(new LabIcon(name,INK));view.setPadding(dp(13),dp(13),dp(13),dp(13));view.setBackground(background(Color.TRANSPARENT,0,24));view.setContentDescription(description);view.setOnClickListener(v->action.run());return view;}
    private void addIcon(LinearLayout parent,String name,String description,Runnable action){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(48),dp(48));params.leftMargin=dp(4);parent.addView(icon(name,description,action),params);}
    private void buildLibrary(){
        filters.clear();navigation.clear();header=column();header.setPadding(dp(16),dp(8),dp(12),dp(4));page.addView(header);
        LinearLayout title=row();ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.eos_logo);logo.setBackground(background(0xffc7172b,0,10));logo.setClipToOutline(true);logo.setContentDescription("EOS Camera Roll logo");title.addView(logo,new LinearLayout.LayoutParams(dp(34),dp(34)));
        TextView name=label("EOS Camera Roll",18,INK,true);name.setSingleLine(true);name.setPadding(dp(10),0,0,0);title.addView(name,new LinearLayout.LayoutParams(0,dp(48),1));name.setGravity(Gravity.CENTER_VERTICAL);
        addIcon(title,"refresh","Refresh camera library",this::refreshLibrary);addIcon(title,"more","Library options",this::options);header.addView(title);
        subtitle=label(libraryStatus,12,MUTED,false);subtitle.setMinHeight(dp(32));subtitle.setGravity(Gravity.CENTER_VERTICAL);subtitle.setContentDescription("Camera connection status. Tap for details");subtitle.setOnClickListener(v->{if(issue!=null)message(issue.title+"\n\n"+issue.detail);else connectionDialog();});header.addView(subtitle);
        LinearLayout tabs=row();String[] titles={"All","Photos","Videos"};for(int i=0;i<3;i++){final int index=i;Button tab=button(titles[i],false,()->{filter=index;applyFilters();});tab.setTextSize(13);tab.setPadding(dp(6),0,dp(6),0);filters.add(tab);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(48),i==0?.8f:1.15f);params.rightMargin=dp(4);tabs.addView(tab,params);}addIcon(tabs,"filter","Filter folders and ratings",this::filterSheet);header.addView(tabs);
        countText=label("All folders",12,MUTED,false);countText.setPadding(dp(2),dp(10),0,dp(8));header.addView(countText);
        folders=new Spinner(this);ratingFilter=button("All ratings",false,this::chooseRatingFilter);updateFolders();folders.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onNothingSelected(AdapterView<?> parent){}@Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){Object tag=folders.getTag();if(tag instanceof List){String chosen=(String)((List<?>)tag).get(position);if(!folder.equals(chosen)){folder=chosen;applyFilters();}}}});
        scanProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);scanProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));scanProgress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));scanProgress.setIndeterminate(true);scanProgress.setVisibility(scanning?View.VISIBLE:View.GONE);page.addView(scanProgress,new LinearLayout.LayoutParams(-1,dp(2)));
        library=new FrameLayout(this);page.addView(library,new LinearLayout.LayoutParams(-1,0,1));grid=new RecyclerView(this);grid.setLayoutManager(new StaggeredGridLayoutManager(columns(),StaggeredGridLayoutManager.VERTICAL));grid.setItemAnimator(null);grid.setClipToPadding(false);grid.setPadding(dp(11),dp(3),dp(11),dp(108));adapter=new Cells();grid.setAdapter(adapter);library.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        emptyPanel=column();emptyPanel.setGravity(Gravity.CENTER);emptyPanel.setPadding(dp(30),0,dp(30),dp(88));ImageView emptyIcon=new ImageView(this);emptyIcon.setImageDrawable(new LabIcon("camera",ACCENT));emptyPanel.addView(emptyIcon,new LinearLayout.LayoutParams(dp(48),dp(48)));gap(emptyPanel,18);empty=label(emptyTitle,21,INK,true);empty.setGravity(Gravity.CENTER);emptyPanel.addView(empty);gap(emptyPanel,8);emptyDescription=label(emptyDetail,14,MUTED,false);emptyDescription.setGravity(Gravity.CENTER);emptyPanel.addView(emptyDescription);gap(emptyPanel,22);emptyAction=button("Connect camera",true,()->{if(all.size()>0)resetFilters();else refreshLibrary();});emptyPanel.addView(emptyAction,new LinearLayout.LayoutParams(dp(190),dp(48)));library.addView(emptyPanel,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout floating=row();floating.setPadding(dp(5),dp(5),dp(5),dp(5));floating.setBackground(background(dark?0xf2242429:0xf2ffffff,dark?0xff37373d:0xffe6e0e3,34));floating.setElevation(dp(8));String[] labels={"Library","Rated","Saved"};String[] icons={"gallery","star","saved"};
        for(int i=0;i<3;i++){final int index=i;LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setContentDescription(labels[i]);item.setOnClickListener(v->{navMode=index;selected.clear();applyFilters();});ImageView glyph=new ImageView(this);glyph.setImageDrawable(new LabIcon(icons[i],i==navMode?ACCENT:MUTED));item.addView(glyph,new LinearLayout.LayoutParams(dp(22),dp(22)));TextView caption=label(labels[i],11,i==navMode?ACCENT:MUTED,true);caption.setGravity(Gravity.CENTER);item.addView(caption);floating.addView(item,new LinearLayout.LayoutParams(0,dp(52),1));navigation.add(item);}
        FrameLayout.LayoutParams dockParams=new FrameLayout.LayoutParams(-1,dp(64),Gravity.BOTTOM);dockParams.setMargins(dp(16),0,dp(88),dp(18));library.addView(floating,dockParams);
        ImageButton download=icon("save","Save photos and videos",this::downloadSheet);download.setImageDrawable(new LabIcon("save",0xffffffff));download.setBackground(background(0xffd92b35,0,28));download.setElevation(dp(6));FrameLayout.LayoutParams downloadParams=new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.BOTTOM|Gravity.RIGHT);downloadParams.setMargins(0,0,dp(16),dp(22));library.addView(download,downloadParams);
        selectionBar=row();selectionBar.setPadding(dp(16),dp(6),dp(12),dp(6));selectionBar.setBackgroundColor(SURFACE);selectionText=label("",14,INK,true);selectionBar.addView(selectionText,new LinearLayout.LayoutParams(0,-2,1));addIcon(selectionBar,"close","Clear selection",()->{selected.clear();applyFilters();});Button rate=button("★",false,this::rateSelection);selectionBar.addView(rate,new LinearLayout.LayoutParams(dp(48),dp(48)));saveSelection=button("Save",true,this::saveSelected);selectionBar.addView(saveSelection);page.addView(selectionBar);selectionBar.setVisibility(View.GONE);
        transferBar=column();transferBar.setPadding(dp(16),dp(6),dp(12),dp(8));transferBar.setBackgroundColor(SURFACE);LinearLayout transferRow=row();transferText=label("",12,INK,false);transferRow.addView(transferText,new LinearLayout.LayoutParams(0,-2,1));cancelTransfer=button("Cancel",false,()->startService(new Intent(this,TransferService.class).setAction("cancel")));transferRow.addView(cancelTransfer);transferBar.addView(transferRow);transferProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);transferProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));transferBar.addView(transferProgress,new LinearLayout.LayoutParams(-1,dp(3)));page.addView(transferBar);applyFilters();
    }
    private int columns(){return getResources().getConfiguration().screenWidthDp>=720?4:getResources().getConfiguration().screenWidthDp>=540?3:2;}
    private void resetFilters(){filter=0;minimumRating=0;navMode=0;folder="";selected.clear();updateFolders();applyFilters();}
    private void filterSheet(){
        LinearLayout form=column();form.setPadding(dp(22),dp(8),dp(22),dp(18));form.addView(label("Camera folder",13,MUTED,true));if(folders.getParent()!=null)((ViewGroup)folders.getParent()).removeView(folders);form.addView(folders,new LinearLayout.LayoutParams(-1,dp(52)));gap(form,16);form.addView(label("Minimum rating",13,MUTED,true));if(ratingFilter.getParent()!=null)((ViewGroup)ratingFilter.getParent()).removeView(ratingFilter);ratingFilter.setText(minimumRating==0?"All ratings":minimumRating+" stars and above");form.addView(ratingFilter,new LinearLayout.LayoutParams(-1,dp(48)));
        new AlertDialog.Builder(this).setTitle("Filter library").setView(form).setPositiveButton("Done",null).setNeutralButton("Reset",(d,w)->resetFilters()).show();
    }
    private void sheet(String title,String[] choices,java.util.function.IntConsumer action){
        Dialog dialog=new Dialog(this);LinearLayout body=column();body.setPadding(dp(20),dp(10),dp(20),dp(22));body.setBackground(background(SURFACE,0,28));View handle=new View(this);handle.setBackground(background(dark?0xff55515a:0xffc8c1c7,0,2));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(34),dp(4));hp.gravity=Gravity.CENTER;hp.bottomMargin=dp(20);body.addView(handle,hp);TextView heading=label(title,22,INK,true);body.addView(heading);gap(body,12);
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();scroll.addView(list);for(int i=0;i<choices.length;i++){final int index=i;TextView option=label(choices[i],16,INK,false);option.setGravity(Gravity.CENTER_VERTICAL);option.setPadding(dp(4),0,dp(4),0);option.setMinHeight(dp(56));option.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(dark?0x33ff777d:0x22bc2030),null,background(0xffffffff,0,12)));option.setOnClickListener(v->{dialog.dismiss();action.accept(index);});list.addView(option,new LinearLayout.LayoutParams(-1,dp(56)));}body.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(56*choices.length),(int)(getResources().getDisplayMetrics().heightPixels*.70))));dialog.setContentView(body);Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.55f);window.setNavigationBarColor(SURFACE);}dialog.show();if(window!=null)window.setLayout(-1,-2);
    }
    private void downloadSheet(){sheet("Save originals",new String[]{selected.isEmpty()?"Save this view":"Save selected media","Copy card to a new folder","Choose download folder"},index->{if(index==0){if(selected.isEmpty())saveItems(new ArrayList<>(visible),false);else saveSelected();}else if(index==1)copyCard();else chooseDownloadFolder();});}
    private void message(String text){new AlertDialog.Builder(this).setMessage(text==null?"The operation could not be completed.":text).setPositiveButton("OK",null).show();}
    private void log(String text){GalleryLog.add(this,text);}

    private void connectionDialog(){
        if(TransferService.busy){message("Let the current download finish before changing the connection.");return;}
        LinearLayout form=column();form.setPadding(dp(22),dp(8),dp(22),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(form);
        form.addView(label("Enter the address shown on your camera.",14,MUTED,false));gap(form,14);
        EditText address=input(form,"Camera address",false);address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        String saved=getSharedPreferences("gallery",0).getString("url",getSharedPreferences("MainActivity",0).getString("url",""));address.setText(session==null?saved:session.root.toString());address.setHint("http://camera-address:port/ccapi");
        Spinner route=new Spinner(this);List<Network> networks=new ArrayList<>();networks.add(null);List<String> routeNames=new ArrayList<>();routeNames.add("Phone hotspot / Automatic");
        ConnectivityManager manager=getSystemService(ConnectivityManager.class);
        for(Network n:manager.getAllNetworks()){NetworkCapabilities c=manager.getNetworkCapabilities(n);LinkProperties p=manager.getLinkProperties(n);if(c!=null&&p!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){networks.add(n);routeNames.add("Camera Wi-Fi: "+p.getInterfaceName());}}
        gap(form,8);form.addView(label("Connection route",13,MUTED,false));route.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,routeNames));form.addView(route,new LinearLayout.LayoutParams(-1,dp(52)));
        if(session!=null&&session.network!=null)for(int i=1;i<networks.size();i++)if(session.network.equals(networks.get(i)))route.setSelection(i);
        Switch auth=new Switch(this);auth.setText("Use a CCAPI account");auth.setTextColor(INK);auth.setMinHeight(dp(48));form.addView(auth);LinearLayout credentials=column();form.addView(credentials);EditText user=input(credentials,"CCAPI username",false),password=input(credentials,"CCAPI password",true);
        if(session!=null){user.setText(session.user);password.setText(session.password);auth.setChecked(!session.user.isEmpty()||getSharedPreferences("gallery",0).getBoolean("accountRequired",false));}credentials.setVisibility(auth.isChecked()?View.VISIBLE:View.GONE);auth.setOnCheckedChangeListener((b,checked)->credentials.setVisibility(checked?View.VISIBLE:View.GONE));
        gap(form,10);Button hotspot=button("Phone hotspot settings",false,()->{try{startActivity(new Intent("android.settings.TETHER_SETTINGS"));}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));}});form.addView(hotspot);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Connect to camera").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Open media",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                String text=address.getText().toString().trim();if(!text.matches("https?://.*"))text="http://"+text;if(new URL(text).getPath().isEmpty())text+="/ccapi";
                URL url=CameraHttp.url(text);CameraSession next=new CameraSession(url,networks.get(route.getSelectedItemPosition()),auth.isChecked()?user.getText().toString():"",auth.isChecked()?password.getText().toString():"",this::log);
                boolean same=session!=null&&CameraHttp.sameOrigin(session.root,next.root);if(same)next.identity=session.identity;
                closeViewer();generation++;scanCancelled=true;finishScan();if(images!=null)images.close();if(session!=null)session.close();session=next;cameraVerified=false;restoring=false;images=createImages();images.networkEnabled=false;selected.clear();if(!same){all.clear();visible.clear();folder="";}updateFolders();applyFilters();
                getSharedPreferences("gallery",0).edit().putString("url",session.root.toString()).putBoolean("accountRequired",auth.isChecked()).putString("route",route.getSelectedItemPosition()==0?"auto":"wifi").apply();dialog.dismiss();scan();
            }catch(Exception e){address.setError(e.getMessage());}
        }));dialog.show();
    }
    private EditText input(LinearLayout parent,String text,boolean password){TextView label=label(text,13,MUTED,false);parent.addView(label);EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(16);input.setTextColor(INK);input.setInputType(InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));input.setSaveEnabled(!password);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);input.setPadding(dp(10),0,dp(10),0);input.setBackground(background(SURFACE,0xffcbd4e1,8));int id=View.generateViewId();input.setId(id);label.setLabelFor(id);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.topMargin=dp(5);p.bottomMargin=dp(10);parent.addView(input,p);return input;}

    private GalleryImages createImages(){return new GalleryImages(this,session,(String key)->{if(!destroyed&&viewer==null&&adapter!=null)adapter.thumbnailChanged(key);});}
    private Network savedRoute(){
        if(!getSharedPreferences("gallery",0).getString("route","auto").equals("wifi"))return null;
        ConnectivityManager manager=getSystemService(ConnectivityManager.class);for(Network network:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(network);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return network;}return null;
    }
    private void restoreLibrary(){
        final int token=generation;String saved=getSharedPreferences("gallery",0).getString("url","");
        catalogWorker.execute(()->{LibrarySnapshot snapshot=null;try{snapshot=LibrarySnapshot.read(this);}catch(FileNotFoundException none){}catch(IOException invalid){log("CACHE: "+invalid.getMessage());}final LibrarySnapshot loaded=snapshot;
            main.post(()->{if(destroyed||token!=generation||session!=null||scanning)return;restoring=false;
                try{URL root=loaded!=null?loaded.root:saved.isEmpty()?null:CameraHttp.url(saved);if(root==null){applyFilters();return;}
                    if(!saved.isEmpty()&&!CameraHttp.sameOrigin(root,CameraHttp.url(saved)))return;
                    session=new CameraSession(root,savedRoute(),"","",this::log);if(loaded!=null){session.identity=loaded.identity;all.clear();all.addAll(loaded.items);}images=createImages();images.networkEnabled=false;libraryStatus=all.isEmpty()?"Refresh to connect":"Cached library · Refresh to reconnect";emptyTitle="Reconnect to your camera";emptyDetail="Your connection is saved. Refresh to load the camera library.";updateFolders();applyFilters();
                }catch(IOException invalid){log("CACHE: "+invalid.getMessage());applyFilters();}
            });
        });
    }
    private void refreshLibrary(){
        if(scanning||TransferService.busy){Toast.makeText(this,"Wait for the current operation to finish",Toast.LENGTH_SHORT).show();return;}
        if(session==null){String url=getSharedPreferences("gallery",0).getString("url","");if(url.isEmpty()){connectionDialog();return;}try{session=new CameraSession(CameraHttp.url(url),savedRoute(),"","",this::log);images=createImages();images.networkEnabled=false;}catch(IOException invalid){connectionDialog();return;}}
        if(getSharedPreferences("gallery",0).getBoolean("accountRequired",false)&&session.user.isEmpty()){connectionDialog();return;}
        if(getSharedPreferences("gallery",0).getString("route","auto").equals("wifi")&&savedRoute()==null){issue=new LibraryIssue("Camera Wi-Fi is disconnected","Join the camera's Wi-Fi network, then refresh. Or open connection settings to use your phone hotspot.");libraryStatus=issue.title;emptyTitle=issue.title;emptyDetail=issue.detail;applyFilters();return;}
        Network route=savedRoute();if(!Objects.equals(session.network,route))try{CameraSession next=new CameraSession(session.root,route,session.user,session.password,this::log);next.identity=session.identity;if(images!=null)images.close();session.close();session=next;images=createImages();images.networkEnabled=false;}catch(IOException error){issue=LibraryIssue.from(error);libraryStatus=issue.title;applyFilters();return;}
        scan();
    }
    private void scan(){
        if(session==null){refreshLibrary();return;}if(scanning||TransferService.busy)return;
        final int token=++generation;final CameraSession current=session;final boolean hadLibrary=!all.isEmpty();scanCancelled=false;scanning=true;cameraVerified=false;restoring=false;issue=null;libraryStatus="Connecting to camera…";emptyTitle="Reading your camera";emptyDetail="Your photos will appear here.";scanProgress.setVisibility(View.VISIBLE);if(images!=null)images.networkEnabled=false;applyFilters();
        catalogWorker.execute(()->{
            MediaCatalog catalog=new MediaCatalog(current.root,current,()->scanCancelled||current.closed||token!=generation,this::log);
            try{
                List<MediaItem> list=catalog.scan(partial->{if(!hadLibrary)main.post(()->{if(destroyed||token!=generation)return;all.clear();all.addAll(partial);applyFilters();});});
                if(catalog.warnings.isEmpty())try{new LibrarySnapshot(current.root,current.identity,list,System.currentTimeMillis()).write(this);}catch(IOException cacheError){log("CACHE: "+cacheError.getMessage());}
                main.post(()->{if(destroyed||token!=generation)return;all.clear();all.addAll(list);finishScan();cameraVerified=true;issue=null;libraryStatus=catalog.warnings.isEmpty()?"Camera connected":"Partial library · Refresh to retry";emptyTitle=catalog.warnings.isEmpty()?"No photos or videos":"Some folders could not be read";emptyDetail=catalog.warnings.isEmpty()?"The camera is connected. There are no supported photos or videos on its card.":"Check the camera and refresh to read the remaining folders.";if(images!=null)images.refresh();updateFolders();applyFilters();if(!catalog.warnings.isEmpty())catalog.warnings.forEach(this::log);});
            }catch(Exception error){log("SCAN: "+error.getMessage());main.post(()->{if(destroyed||token!=generation)return;finishScan();cameraVerified=false;issue=LibraryIssue.from(error);libraryStatus=issue.title+(all.isEmpty()?"":" · Cached library");emptyTitle=issue.title;emptyDetail=issue.detail;if(images!=null)images.networkEnabled=false;applyFilters();});}
        });
    }
    private void finishScan(){scanning=false;scanProgress.setVisibility(View.GONE);}
    private String friendly(Exception error){LibraryIssue failure=LibraryIssue.from(error);return failure.title.equals("Could not read the library")?(error.getMessage()==null?failure.detail:error.getMessage()):failure.title+"\n\n"+failure.detail;}
    private void updateFolders(){
        TreeSet<String> unique=new TreeSet<>();for(MediaItem i:all)unique.add(i.folder);List<String> values=new ArrayList<>();values.add("");values.addAll(unique);List<String> names=new ArrayList<>();names.add("All folders");names.addAll(unique);int selection=Math.max(0,values.indexOf(folder));folders.setTag(values);folders.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));folders.setSelection(selection);
    }
    private boolean inCollection(MediaItem item){if(session==null)return true;int rating=states.rating(session.key(item));return (folder.isEmpty()||folder.equals(item.folder))&&MediaKey.matchesRating(rating,minimumRating)&&(navMode!=1||rating>0)&&(navMode!=2||states.saved(session.key(item)));}
    private void applyFilters(){
        visible.clear();int photos=0,videos=0;for(MediaItem item:all)if(inCollection(item)){if(item.video)videos++;else photos++;if(filter==0||(filter==1&&!item.video)||(filter==2&&item.video))visible.add(item);}
        Comparator<MediaItem> sort=Comparator.comparing((MediaItem item)->item.name,String.CASE_INSENSITIVE_ORDER).thenComparing(item->item.folder);visible.sort(descending?sort.reversed():sort);adapter.notifyDataSetChanged();
        String[] labels={"All  "+(photos+videos),"Photos  "+photos,"Videos  "+videos};for(int i=0;i<filters.size();i++){Button tab=filters.get(i);tab.setText(labels[i]);tab.setTextColor(filter==i?ACCENT:MUTED);tab.setBackground(background(filter==i?(dark?0xff342025:0xffffe8eb):Color.TRANSPARENT,0,24));}
        String[] icons={"gallery","star","saved"};for(int i=0;i<navigation.size();i++){LinearLayout item=navigation.get(i);((ImageView)item.getChildAt(0)).setImageDrawable(new LabIcon(icons[i],i==navMode?ACCENT:MUTED));((TextView)item.getChildAt(1)).setTextColor(i==navMode?ACCENT:MUTED);item.setBackground(background(i==navMode?(dark?0xff3b242a:0xffffe8eb):Color.TRANSPARENT,0,28));item.setSelected(i==navMode);}
        subtitle.setText(libraryStatus);subtitle.setTextColor(issue!=null?ACCENT:MUTED);countText.setText((folder.isEmpty()?navMode==1?"Rated media":navMode==2?"Saved originals":"All folders":folder.substring(folder.lastIndexOf('/')+1))+(minimumRating>0?"   ★ "+minimumRating+"+":""));
        boolean noMatches=!all.isEmpty();empty.setText(noMatches?"No matches":emptyTitle);emptyDescription.setText(noMatches?"Try another filter or collection.":emptyDetail);emptyAction.setText(noMatches?"Clear filters":session==null?"Connect camera":"Refresh");emptyAction.setEnabled(!scanning);emptyPanel.setVisibility(visible.isEmpty()?View.VISIBLE:View.GONE);grid.setVisibility(visible.isEmpty()?View.GONE:View.VISIBLE);updateSelection();
    }
    private void toggle(MediaItem item){if(!selected.remove(item))selected.add(item);adapter.notifyDataSetChanged();updateSelection();}
    private void updateSelection(){selectionBar.setVisibility(selected.isEmpty()?View.GONE:View.VISIBLE);selectionText.setText(selected.size()+" selected");saveSelection.setEnabled(!TransferService.busy&&!scanning);}
    private void updateTransfer(){
        String text=TransferService.summary;boolean active=TransferService.busy;transferBar.setVisibility(active?View.VISIBLE:View.GONE);transferText.setText(text);cancelTransfer.setVisibility(active?View.VISIBLE:View.GONE);transferProgress.setIndeterminate(TransferService.percent<0);transferProgress.setProgress(Math.max(0,TransferService.percent));
        if(viewer!=null&&viewerTransferProgress!=null){viewerTransferText.setText(text);viewerTransferText.setVisibility(active?View.VISIBLE:View.GONE);viewerTransferProgress.setVisibility(active?View.VISIBLE:View.GONE);viewerTransferProgress.setIndeterminate(TransferService.percent<0);viewerTransferProgress.setProgress(Math.max(0,TransferService.percent));updateViewerSaved();}
        if(images!=null)images.paused=viewer!=null||active;updateSelection();
        if(!active){if(adapter!=null)adapter.notifyDataSetChanged();long done=getSharedPreferences("gallery",0).getLong("completion",0);if(resumed&&done>seenCompletion){seenCompletion=done;Toast.makeText(this,getSharedPreferences("gallery",0).getString("lastTransfer","Transfer finished"),Toast.LENGTH_LONG).show();}openPendingLightroom();}
    }
    private void chooseRatingFilter(){sheet("Minimum rating",new String[]{"All ratings","1 star and above","2 stars and above","3 stars and above","4 stars and above","5 stars"},index->{minimumRating=index;ratingFilter.setText(index==0?"All ratings":index+" stars and above");applyFilters();});}
    private void rateSelection(){sheet("Rate selected media",new String[]{"Clear rating","★","★★","★★★","★★★★","★★★★★"},index->{for(MediaItem item:selected)states.rate(session.key(item),index);applyFilters();});}
    private void copyCard(){if(!canDownload())return;sheet("Copy to a new folder",new String[]{"All photos","All videos","All photos and videos"},index->{List<MediaItem> items=new ArrayList<>();for(MediaItem item:all)if(index==2||(index==0&&!item.video)||(index==1&&item.video))items.add(item);saveItems(items,true);});}
    private void options(){
        String[] options={"Refresh library","Choose download folder","Appearance",descending?"Sort A to Z":"Sort Z to A",showNames?"Hide filenames":"Show filenames","Select this view","Clear selection","Saved marks","Connection log","Connection settings","About"};
        sheet("Library options",options,which->{
            switch(which){case 0:refreshLibrary();break;case 1:chooseDownloadFolder();break;case 2:appearance();break;case 3:descending=!descending;applyFilters();break;case 4:showNames=!showNames;getSharedPreferences("gallery",0).edit().putBoolean("names",showNames).apply();adapter.notifyDataSetChanged();break;case 5:selected.addAll(visible);applyFilters();break;case 6:selected.clear();applyFilters();break;case 7:message("✓ marks a completed original download from this app. Files saved with older versions or imported into Lightroom are not marked. Ratings are stored in this app.");break;case 8:showLog();break;case 9:connectionDialog();break;case 10:about();break;}
        });
    }
    private void appearance(){String[] modes={"Dark","Light","System"};sheet("Appearance",modes,index->{getSharedPreferences("gallery",0).edit().putString("appearance",modes[index]).apply();closeViewer();applyAppearance();styleSystemBars();root.setBackgroundColor(PAPER);root.removeAllViews();page=column();root.addView(page,new FrameLayout.LayoutParams(-1,-1));buildLibrary();updateTransfer();});}
    private void about(){new AlertDialog.Builder(this).setTitle("EOS Camera Roll "+appVersion()).setMessage("Original photos and videos from your camera. RAW rendering uses LibRaw, including support for EOS R50 V.\n\nLocal ratings stay in this app. Lightroom owns its imported copy and edits. Temporary handoff files are removed after Lightroom releases them, with cleanup after 24 hours if interrupted.").setPositiveButton("Close",null).setNeutralButton("LibRaw license",(d,w)->{try(InputStream in=getAssets().open("LibRaw-LICENSE.txt")){message(new String(CameraHttp.readBounded(in,65536),java.nio.charset.StandardCharsets.UTF_8));}catch(IOException e){message(e.getMessage());}}).show();}
    private void showLog(){ScrollView scroll=new ScrollView(this);TextView text=label(GalleryLog.read(this),12,INK,false);text.setTypeface(Typeface.MONOSPACE);text.setTextIsSelectable(true);text.setPadding(dp(16),dp(12),dp(16),dp(12));scroll.addView(text);new AlertDialog.Builder(this).setTitle("Connection log").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Share",(d,w)->{Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,GalleryLog.read(this));startActivity(Intent.createChooser(share,"Share media log"));}).show();}

    private final class Cells extends RecyclerView.Adapter<Cells.Holder> {
        final class Holder extends RecyclerView.ViewHolder {final MediaCell cell;Holder(MediaCell cell){super(cell);this.cell=cell;}}
        @Override public int getItemCount(){return visible.size();}
        @Override public Holder onCreateViewHolder(ViewGroup parent,int type){MediaCell cell=new MediaCell();RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,dp(160));params.setMargins(dp(5),dp(5),dp(5),dp(5));cell.setLayoutParams(params);return new Holder(cell);}
        @Override public void onBindViewHolder(Holder holder,int position){
            MediaItem item=visible.get(position);MediaCell cell=holder.cell;String key=session.key(item);float ratio=images==null?1.25f:images.ratio(item);int width=(grid.getWidth()>0?grid.getWidth()-grid.getPaddingLeft()-grid.getPaddingRight():getResources().getDisplayMetrics().widthPixels-dp(22))/columns()-dp(10);int height=Math.max(dp(110),Math.min(dp(290),(int)(width/Math.max(.55f,Math.min(1.8f,ratio)))));ViewGroup.LayoutParams params=cell.getLayoutParams();if(params.height!=height){params.height=height;cell.setLayoutParams(params);}
            int rating=states.rating(key);boolean saved=states.saved(key),chosen=selected.contains(item);cell.name.setText(item.name);cell.name.setVisibility(showNames?View.VISIBLE:View.GONE);cell.badge.setText(item.video?"▶":item.raw()?"RAW":"");cell.badge.setVisibility(item.video||item.raw()?View.VISIBLE:View.GONE);cell.saved.setVisibility(saved?View.VISIBLE:View.GONE);cell.stars.setText("★ "+rating);cell.stars.setVisibility(rating>0?View.VISIBLE:View.GONE);cell.check.setText("✓");cell.check.setVisibility(chosen?View.VISIBLE:View.GONE);cell.setForeground(chosen?background(0x26d92b35,0xffff777d,16):null);cell.setContentDescription(item.name+", "+(item.video?"video":item.raw()?"RAW photo":"photo")+(saved?", saved":"")+(rating>0?", "+rating+" stars":"")+(chosen?", selected":""));cell.setOnClickListener(v->{int index=holder.getBindingAdapterPosition();if(index==RecyclerView.NO_POSITION)return;if(!selected.isEmpty())toggle(visible.get(index));else{viewingItems=new ArrayList<>(visible);openViewer(index);}});cell.setOnLongClickListener(v->{int index=holder.getBindingAdapterPosition();if(index!=RecyclerView.NO_POSITION)toggle(visible.get(index));return true;});if(images!=null)images.thumbnail(item,cell.image);
        }
        void thumbnailChanged(String key){Runnable update=()->{if(session==null)return;for(int i=0;i<visible.size();i++)if(session.key(visible.get(i)).equals(key)){notifyItemChanged(i);break;}};if(grid.isComputingLayout())grid.post(update);else update.run();}
    }
    private final class MediaCell extends FrameLayout {
        final ImageView image;final TextView badge,check,name,saved,stars;
        private final Path clip=new Path();
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);clip.reset();clip.addRoundRect(0,0,w,h,dp(16),dp(16),Path.Direction.CW);}
        @Override protected void dispatchDraw(Canvas canvas){int save=canvas.save();canvas.clipPath(clip);super.dispatchDraw(canvas);canvas.restoreToCount(save);}
        MediaCell(){super(GalleryActivity.this);setBackground(background(SURFACE,0,16));setClipToOutline(true);image=new ImageView(GalleryActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);addView(image,new FrameLayout.LayoutParams(-1,-1));badge=overlay("",11,0xfff5f3f4);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);bp.setMargins(dp(8),0,0,dp(8));addView(badge,bp);saved=overlay("✓",14,0xff9be0b6);FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(29),dp(28),Gravity.BOTTOM|Gravity.RIGHT);sp.setMargins(0,0,dp(8),dp(8));addView(saved,sp);stars=overlay("",11,0xffe8bd66);FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);rp.setMargins(dp(8),dp(8),0,0);addView(stars,rp);check=overlay("✓",15,0xffffffff);check.setBackground(background(0xffd92b35,0xffffffff,16));FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.TOP|Gravity.RIGHT);cp.setMargins(0,dp(8),dp(8),0);addView(check,cp);name=overlay("",10,0xfff5f3f4);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,dp(24),Gravity.BOTTOM);np.bottomMargin=dp(40);addView(name,np);}
    }
    private TextView overlay(String text,int size,int color){TextView label=label(text,size,color,true);label.setGravity(Gravity.CENTER);label.setPadding(dp(7),dp(3),dp(7),dp(3));label.setBackground(background(0xbb121215,0,14));return label;}

    private void openViewer(int index){
        closeViewer();if(index<0||index>=viewingItems.size())return;resumePosition=0;viewingIndex=index;viewing=viewingItems.get(index);int token=++viewerGeneration;MediaItem item=viewing;CameraSession current=session;
        if(images!=null)images.paused=true;viewer=new FrameLayout(this);viewer.setBackgroundColor(PAPER);root.addView(viewer,new FrameLayout.LayoutParams(-1,-1));LinearLayout content=column();viewer.addView(content,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=row();top.setPadding(dp(12),dp(12),dp(12),dp(8));addIcon(top,"back","Close viewer",this::closeViewer);viewerTitle=label(item.name,14,INK,true);viewerTitle.setMaxLines(2);viewerTitle.setPadding(dp(12),0,0,0);top.addView(viewerTitle,new LinearLayout.LayoutParams(0,-2,1));addIcon(top,"info","File information",()->message(viewerDetail.getText().toString()));content.addView(top);
        stage=new FrameLayout(this);content.addView(stage,new LinearLayout.LayoutParams(-1,0,1));stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(prepared&&player!=null&&(r-l!=or-ol||b-t!=ob-ot))try{fitVideo(player.getVideoWidth(),player.getVideoHeight());}catch(IllegalStateException ignored){}});photo=new ZoomPhotoView(this);stage.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        playbackText=label(item.video?"Ready to stream from camera":"Loading preview…",12,MUTED,false);playbackText.setGravity(Gravity.CENTER);playbackText.setPadding(dp(18),dp(6),dp(18),dp(6));content.addView(playbackText);
        viewerDetail=label(item.extension+" • "+item.folder,12,MUTED,false);viewerDetail.setGravity(Gravity.CENTER);viewerDetail.setPadding(dp(16),0,dp(16),dp(8));viewerDetail.setVisibility(View.GONE);content.addView(viewerDetail);
        previewProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);previewProgress.setIndeterminate(true);previewProgress.setVisibility(item.video?View.GONE:View.VISIBLE);content.addView(previewProgress,new LinearLayout.LayoutParams(-1,dp(3)));
        if(item.video){
            seek=new SeekBar(this);seek.setEnabled(false);content.addView(seek,new LinearLayout.LayoutParams(-1,dp(40)));seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar b,int p,boolean fromUser){}@Override public void onStartTrackingTouch(SeekBar b){}@Override public void onStopTrackingTouch(SeekBar b){if(prepared&&player!=null)player.seekTo(b.getProgress());}});
            play=button("Play video",true,()->{if(!cameraVerified){message("Refresh the library to reconnect before streaming this video.");return;}if(TransferService.busy){message("Wait for the original download to finish before streaming.");return;}if(prepared&&player!=null){if(player.isPlaying()){player.pause();play.setText("Play");getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}else{player.start();play.setText("Pause");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}}else startPlayback(token,item);});LinearLayout controls=row();controls.setGravity(Gravity.CENTER);controls.addView(play,new LinearLayout.LayoutParams(dp(180),dp(48)));content.addView(controls);
        }
        LinearLayout ratings=row();ratings.setGravity(Gravity.CENTER);ratingButtons.clear();int rating=states.rating(current.key(item));
        for(int i=1;i<=5;i++){final int stars=i;Button rate=button(i<=rating?"★":"☆",false,()->{int now=states.rating(current.key(item));states.rate(current.key(item),now==stars?0:stars);updateRatingButtons();});rate.setBackgroundColor(Color.TRANSPARENT);rate.setTextSize(25);rate.setTextColor(dark?0xffe8bd66:0xff875900);rate.setContentDescription("Rate "+i+" stars");ratingButtons.add(rate);ratings.addView(rate,new LinearLayout.LayoutParams(dp(48),dp(48)));}content.addView(ratings);
        viewerTransferText=label("",12,INK,false);viewerTransferText.setPadding(dp(16),dp(6),dp(16),dp(6));viewerTransferText.setVisibility(View.GONE);content.addView(viewerTransferText);viewerTransferProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);viewerTransferProgress.setVisibility(View.GONE);content.addView(viewerTransferProgress,new LinearLayout.LayoutParams(-1,dp(3)));
        LinearLayout bottom=row();bottom.setPadding(dp(12),dp(10),dp(12),dp(12));ImageButton previous=icon("previous","Previous file",()->openViewer(viewingIndex-1));previous.setEnabled(index>0);previous.setAlpha(index>0?1f:.35f);bottom.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(48)));
        viewerSave=button("Save",true,()->saveOne(item));LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(0,dp(48),1);saveParams.setMargins(dp(8),0,dp(8),0);bottom.addView(viewerSave,saveParams);
        if(!item.video){Button lr=button("Lr",false,()->sendLightroom(item));lr.setContentDescription("Edit original in Lightroom");bottom.addView(lr,new LinearLayout.LayoutParams(dp(56),dp(48)));}
        ImageButton next=icon("next","Next file",()->openViewer(viewingIndex+1));next.setEnabled(index<viewingItems.size()-1);next.setAlpha(index<viewingItems.size()-1?1f:.35f);LinearLayout.LayoutParams nextParams=new LinearLayout.LayoutParams(dp(48),dp(48));nextParams.leftMargin=dp(8);bottom.addView(next,nextParams);content.addView(bottom);updateViewerSaved();
        images.preview(item,bitmap->{if(viewer!=null&&token==viewerGeneration)photo.setImageBitmap(bitmap);},note->{if(viewer!=null&&token==viewerGeneration){playbackText.setText(note);if(note.startsWith("Rendering")){previewProgress.setVisibility(View.VISIBLE);previewProgress.setIndeterminate(true);}}},(bytes,total)->{if(viewer!=null&&token==viewerGeneration){previewProgress.setVisibility(bytes<0?View.GONE:View.VISIBLE);previewProgress.setIndeterminate(total<=0);previewProgress.setProgress(total>0?(int)(bytes*100/total):0);}});
        updateTransfer();
        viewerWorker.execute(()->{if(destroyed||token!=viewerGeneration)return;try{Object response=current.json(MediaCatalog.query(item.url,"kind=info"));if(response instanceof JSONObject){JSONObject info=(JSONObject)response;long bytes=info.optLong("filesize",-1);String date=info.optString("lastmodifieddate","");main.post(()->{if(viewer!=null&&token==viewerGeneration)viewerDetail.setText(item.extension+(bytes>=0?" • "+TransferService.human(bytes):"")+"\n"+item.folder+(date.isEmpty()?"":" • "+date));});}}catch(Exception e){log("Media information unavailable: "+e.getMessage());}});
        main.post(playbackTick);
    }
    private void startPlayback(int token,MediaItem item){
        if(playbackStarting)return;final int playbackToken=++playbackGeneration;playbackStarting=true;play.setEnabled(false);playbackText.setText("Opening video stream…");
        texture=new TextureView(this);stage.addView(texture,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            @Override public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){if(playbackToken==playbackGeneration)prepareVideo(token,playbackToken,item,t);}
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t){return true;}
            @Override public void onSurfaceTextureUpdated(SurfaceTexture t){}
        });
    }
    private void prepareVideo(int token,int playbackToken,MediaItem item,SurfaceTexture surfaceTexture){
        CameraSession current=session;File temp=new File(getCacheDir(),"video-"+UUID.randomUUID()+".buffer");RemoteMediaFile source=new RemoteMediaFile(item.url,current.opener(),current.user,current.password,temp,this::log);remote=source;
        viewerWorker.execute(()->{
            try{if(destroyed||token!=viewerGeneration||playbackToken!=playbackGeneration){source.close();return;}source.prepare();main.post(()->{
                if(destroyed||viewer==null||token!=viewerGeneration||playbackToken!=playbackGeneration){source.close();return;}
                try{
                    player=new MediaPlayer();surface=new Surface(surfaceTexture);player.setSurface(surface);player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
                    player.setDataSource(new MediaDataSource(){@Override public int readAt(long position,byte[] buffer,int offset,int size)throws IOException{return source.readAt(position,buffer,offset,size);}@Override public long getSize(){return source.size();}@Override public void close(){source.close();}});
                    player.setOnPreparedListener(mp->{if(token!=viewerGeneration||playbackToken!=playbackGeneration)return;prepared=true;playbackStarting=false;play.setEnabled(true);seek.setEnabled(true);seek.setMax(mp.getDuration());if(resumePosition>0){mp.seekTo(resumePosition);resumePosition=0;}if(resumed){mp.start();play.setText("Pause");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}else play.setText("Play");});
                    player.setOnVideoSizeChangedListener((mp,w,h)->{if(token==viewerGeneration&&playbackToken==playbackGeneration)fitVideo(w,h);});
                    player.setOnCompletionListener(mp->{if(playbackToken!=playbackGeneration)return;play.setText("Play again");getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});
                    player.setOnErrorListener((mp,what,extra)->{if(playbackToken!=playbackGeneration)return true;log("VIDEO: MediaPlayer error "+what+" / "+extra);playbackFailure("This video cannot be streamed with the current camera/phone combination. Save the original to play it in another app.");return true;});
                    player.prepareAsync();
                }catch(Exception e){log("VIDEO: "+e.getMessage());playbackFailure("Playback could not start. You can still save the original.");}
            });}catch(Exception e){log("VIDEO: "+e.getMessage());main.post(()->{if(viewer!=null&&token==viewerGeneration&&playbackToken==playbackGeneration)playbackFailure(friendly(e));});}
        });
    }
    private void fitVideo(int width,int height){if(texture==null||stage==null||width<=0||height<=0||stage.getWidth()==0||stage.getHeight()==0)return;float ratio=Math.min(stage.getWidth()/(float)width,stage.getHeight()/(float)height);texture.setLayoutParams(new FrameLayout.LayoutParams((int)(width*ratio),(int)(height*ratio),Gravity.CENTER));}
    private void playbackFailure(String message){stopPlayer();if(texture!=null&&stage!=null){stage.removeView(texture);texture=null;}playbackText.setText(message);if(play!=null){play.setEnabled(true);play.setText("Retry playback");}}
    private void stopPlayer(){playbackGeneration++;prepared=false;playbackStarting=false;RemoteMediaFile source=remote;remote=null;if(source!=null)source.close();if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}if(surface!=null){surface.release();surface=null;}if(texture!=null&&stage!=null){stage.removeView(texture);texture=null;}if(play!=null){play.setText("Play video");play.setEnabled(true);}getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void closeViewer(){viewerGeneration++;main.removeCallbacks(playbackTick);stopPlayer();if(viewer!=null){root.removeView(viewer);viewer=null;}viewing=null;texture=null;if(images!=null){images.cancelPreview();images.paused=TransferService.busy;}if(adapter!=null){applyFilters();}if(photo!=null)photo.setImageDrawable(null);photo=null;viewerSave=null;viewerTransferText=null;viewerTransferProgress=null;}
    private static String time(int millis){int seconds=Math.max(0,millis/1000);return seconds>=3600?String.format(Locale.US,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);}

    private void updateRatingButtons(){if(viewing==null)return;int rating=states.rating(session.key(viewing));for(int i=0;i<ratingButtons.size();i++){ratingButtons.get(i).setText(i<rating?"★":"☆");ratingButtons.get(i).setContentDescription("Rate "+(i+1)+" stars"+(i<rating?", selected":""));}}
    private void updateViewerSaved(){if(viewerSave==null||viewing==null||session==null)return;boolean saved=states.saved(session.key(viewing));viewerSave.setText(saved?"✓ Saved":"Save");viewerSave.setEnabled(!TransferService.busy);}
    private void saveOne(MediaItem item){saveItems(Collections.singletonList(item),false);}
    private void saveSelected(){saveItems(new ArrayList<>(selected),false);}
    private void saveItems(List<MediaItem> items,boolean newFolder){if(!canDownload()||items.isEmpty())return;stopPlayer();if(images!=null)images.cancelPreview();pendingItems=items;pendingNewFolder=newFolder;String stored=getSharedPreferences("gallery",0).getString("downloadTree","");if(stored.isEmpty())chooseDownloadFolder();else startPending(Uri.parse(stored));}
    private void chooseDownloadFolder(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),92);}
    private void startPending(Uri destination){
        if(pendingItems.isEmpty())return;
        try{if(images!=null)images.paused=true;TransferService.start(this,session,pendingItems,destination,pendingNewFolder,false);selected.clear();pendingItems=Collections.emptyList();updateTransfer();requestNotifications();}
        catch(Exception e){message(friendly(e));updateTransfer();}
    }
    private void sendLightroom(MediaItem item){if(!canDownload())return;if(!LightroomProvider.installed(this)){message("Install Lightroom on this phone to edit the original.");return;}stopPlayer();images.cancelPreview();try{TransferService.start(this,session,Collections.singletonList(item),null,false,true);updateTransfer();requestNotifications();}catch(Exception e){message(friendly(e));}}
    private void openPendingLightroom(){if(!resumed||TransferService.busy)return;SharedPreferences preferences=getSharedPreferences("gallery",0);String uri=preferences.getString("handoff","");if(uri.isEmpty())return;String key=preferences.getString("handoffKey","");preferences.edit().remove("handoff").commit();try{LightroomProvider.launch(this,Uri.parse(uri),preferences.getString("handoffMime","image/*"));states.markSent(key);}catch(Exception e){message("Could not open Lightroom. Tap Lr to try again.");}}
    private boolean canDownload(){if(session==null){connectionDialog();return false;}if(!cameraVerified){message("Refresh the library to reconnect before saving originals.");return false;}if(scanning){message("Finish reading the card before starting a transfer.");return false;}if(TransferService.busy){message("A transfer is already running.");return false;}return true;}
    private void requestNotifications(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},93);}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code!=92)return;if(result!=RESULT_OK||data==null||data.getData()==null){pendingItems=Collections.emptyList();return;}
        try{Uri destination=data.getData();int permission=Intent.FLAG_GRANT_WRITE_URI_PERMISSION;if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0)throw new SecurityException("Choose a writable folder");if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)permission|=Intent.FLAG_GRANT_READ_URI_PERMISSION;getContentResolver().takePersistableUriPermission(destination,permission);getSharedPreferences("gallery",0).edit().putString("downloadTree",destination.toString()).apply();if(pendingItems.isEmpty())Toast.makeText(this,"Download folder set",Toast.LENGTH_SHORT).show();else startPending(destination);}catch(Exception e){message(friendly(e));}
    }
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);((StaggeredGridLayoutManager)grid.getLayoutManager()).setSpanCount(columns());adapter.notifyDataSetChanged();}
    @Override public void onBackPressed(){if(viewer!=null)closeViewer();else if(!selected.isEmpty()){selected.clear();applyFilters();}else super.onBackPressed();}
    @Override protected void onResume(){super.onResume();resumed=true;if(images!=null)images.paused=viewer!=null||TransferService.busy;if(transferBar!=null)updateTransfer();}
    @Override protected void onStop(){resumed=false;if(player!=null&&prepared)try{resumePosition=player.getCurrentPosition();}catch(IllegalStateException ignored){}if(player!=null||remote!=null||playbackStarting){stopPlayer();if(viewer!=null&&viewing!=null&&viewing.video)playbackText.setText("Tap Play video to continue streaming.");}if(images!=null)images.paused=true;super.onStop();}
    @Override protected void onDestroy(){destroyed=true;scanCancelled=true;generation++;closeViewer();if(images!=null)images.close();if(session!=null)session.close();catalogWorker.shutdownNow();viewerWorker.shutdownNow();TransferService.observers.remove(transferObserver);super.onDestroy();}
}
