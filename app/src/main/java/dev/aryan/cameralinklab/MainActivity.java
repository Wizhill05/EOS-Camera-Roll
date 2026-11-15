package dev.aryan.cameralinklab;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import javax.net.SocketFactory;

public final class MainActivity extends Activity {
    private static final int INK=0xff14283f, MUTED=0xff516379, BLUE=0xff245fa6, PAPER=0xfff3f7fc;
    private static final String SERVICE="urn:schemas-canon-com:service:ICPO-WFTEOSSystemService:1";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final StringBuilder transcript=new StringBuilder();
    private final ArrayList<Network> routes=new ArrayList<>();
    private final ArrayList<Button> taskButtons=new ArrayList<>();
    private ConnectivityManager connectivity;
    private WifiManager wifi;
    private Spinner route;
    private EditText ssid, wifiPass, cameraUrl, cameraHost, account, accountPass;
    private TextView networkInfo, status, logView, hotspotInfo;
    private LinearLayout results;
    private ProgressBar progress;
    private WifiManager.LocalOnlyHotspotReservation hotspot;
    private boolean hotspotStarting, destroyed, busy;
    private int hotspotGeneration;
    private Runnable afterPermission;
    private ConnectivityManager.NetworkCallback joinedCallback;
    private volatile Network joinedNetwork;
    private volatile PtpIp ptp;
    private volatile CameraHttp http;
    private volatile DatagramSocket discovery;
    private volatile boolean cancelled;
    private Future<?> task;
    private URL pendingDownload;
    private Network pendingNetwork;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        connectivity=getSystemService(ConnectivityManager.class);
        wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        getWindow().setStatusBarColor(PAPER);getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(PAPER);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(22),dp(24),dp(22),dp(32));
        page.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(dp(22),dp(24)+insets.getSystemWindowInsetTop(),dp(22),dp(32)+insets.getSystemWindowInsetBottom());return insets;
        });
        scroll.addView(page);setContentView(scroll);
        text(page,"Camera Link Lab",30,INK,true);
        text(page,"R50 V connection tests",17,MUTED,false);
        text(page,"Connect locally without Bluetooth. Keep this screen open during tests. The camera’s card is never changed.",15,INK,false);
        status=text(page,"Ready to test",18,BLUE,true);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);page.addView(progress);

        section(page,"1. Make the Wi-Fi link");
        text(page,"Recommended for EOS Utility: on the camera choose Connect to EOS Utility → Add a device. Join the Wi-Fi name shown on the camera.",15,INK,false);
        ssid=field(page,"Camera Wi-Fi name (SSID)",false);
        wifiPass=field(page,"Camera Wi-Fi password",true);
        button(page,"Join camera Wi-Fi",() -> permissions(() -> joinCamera()));
        button(page,"Open phone Wi-Fi settings",() -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        text(page,"For CCAPI without a router: start a phone hotspot below, then connect the camera’s CCAPI setup to that network. Enter the camera URL shown after it connects.",15,INK,false);
        button(page,"Start phone hotspot",() -> permissions(() -> startHotspot()));
        hotspotInfo=text(page,"Local hotspot is off. Starting it may change your phone’s current Wi-Fi connection.",14,MUTED,false);
        button(page,"Stop hotspot / release requested Wi-Fi",() -> releaseLinks());

        section(page,"2. Check the network route");
        route=new Spinner(this);page.addView(route,new LinearLayout.LayoutParams(-1,dp(52)));
        networkInfo=text(page,"",14,MUTED,false);networkInfo.setTextIsSelectable(true);
        button(page,"Refresh network details",() -> refreshNetworks());
        text(page,"Use the Wi-Fi route after joining the camera. Use Automatic for a camera connected to this phone’s hotspot. A VPN can affect Automatic routing.",14,MUTED,false);

        section(page,"3. Test a camera service");
        cameraUrl=field(page,"CCAPI URL shown on camera",false);cameraUrl.setText(getPreferences(0).getString("url","http://192.168.0.1:8080/ccapi"));
        cameraUrl.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        account=field(page,"CCAPI username (if enabled)",false);
        accountPass=field(page,"CCAPI password (if enabled)",true);
        text(page,"CCAPI: Advanced connection → Camera Control API. HTTP and HTTPS are supported. HTTPS uses Android certificate validation; certificate errors appear in the log.",14,MUTED,false);
        action(page,"Test CCAPI / open endpoint",() -> browse());
        cameraHost=field(page,"EOS Utility camera IP address",false);cameraHost.setText(getPreferences(0).getString("host","192.168.0.1"));
        text(page,"The initial IP is a suggestion, not a detected camera. Use Discover or check the camera’s network settings.",14,MUTED,false);
        action(page,"Discover EOS camera",() -> discover());
        action(page,"Test EOS Utility pairing",() -> testPtp());
        button(page,"Cancel current test",() -> cancel());
        results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);page.addView(results);

        section(page,"Connection log");
        text(page,"Includes camera addresses and filenames, but excludes passwords and hotspot credentials. Share it after a failed test.",14,MUTED,false);
        button(page,"Share log",() -> shareLog());
        button(page,"Clear log",() -> {transcript.setLength(0);logView.setText("");persistLog();});
        logView=text(page,"",12,INK,false);logView.setTypeface(Typeface.MONOSPACE);logView.setTextIsSelectable(true);
        try {transcript.append(new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(),"connection-log.txt").toPath()),StandardCharsets.UTF_8));logView.setText(transcript);}
        catch(IOException ignored){}
        refreshNetworks();
        log("APP: Camera Link Lab 0.3. Folder and file links in JSON arrays are supported; credentials are never logged.");
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout parent,String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(6),0,dp(6));t.setLineSpacing(dp(3),1);
        if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));parent.addView(t);return t;
    }
    private void section(LinearLayout page,String title){TextView t=text(page,title,21,INK,true);t.setPadding(0,dp(24),0,dp(7));}
    private EditText field(LinearLayout page,String hint,boolean secret){
        TextView label=text(page,hint,14,MUTED,false);label.setPadding(0,dp(10),0,0);
        EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(16);e.setTextColor(INK);e.setPadding(dp(12),dp(10),dp(12),dp(10));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(7));bg.setStroke(dp(1),0xffa5b7cb);e.setBackground(bg);
        e.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));e.setSaveEnabled(!secret);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);label.setLabelFor(View.generateViewId());e.setId(label.getLabelFor());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.bottomMargin=dp(5);page.addView(e,p);return e;
    }
    private Button button(LinearLayout page,String label,Runnable click){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(BLUE);b.setMinHeight(dp(48));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(4);page.addView(b,p);b.setOnClickListener(v->{try{click.run();}catch(Exception e){failure(e);}});return b;
    }
    private void action(LinearLayout page,String label,Runnable click){taskButtons.add(button(page,label,click));}

    private void permissions(Runnable next){
        if(busy){log("Finish or cancel the current test before changing Wi-Fi.");return;}
        ArrayList<String> missing=new ArrayList<>();
        // The join API requires fine location even on newer Android; hotspot needs Nearby Wi-Fi on 33+.
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);missing.add(Manifest.permission.ACCESS_FINE_LOCATION);}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if(missing.isEmpty()){next.run();return;}
        afterPermission=next;requestPermissions(missing.toArray(new String[0]),10);
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants){
        super.onRequestPermissionsResult(code,names,grants);
        if(code==10){Runnable next=afterPermission;afterPermission=null;for(int g:grants)if(g!=PackageManager.PERMISSION_GRANTED){log("PERMISSION: Wi-Fi permission denied. You can join in system Wi-Fi settings, then test the camera.");return;}if(grants.length>0&&next!=null)next.run();}
    }
    private void joinCamera(){
        String name=ssid.getText().toString(),password=wifiPass.getText().toString();
        if(name.isEmpty()){log("Enter the Wi-Fi name displayed by the camera.");return;}
        releaseLinks();
        WifiNetworkSpecifier.Builder spec=new WifiNetworkSpecifier.Builder().setSsid(name);
        if(!password.isEmpty())spec.setWpa2Passphrase(password);
        NetworkRequest request=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec.build()).build();
        joinedCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){joinedNetwork=network;main.post(()->{if(destroyed)return;log("WI-FI: requested camera network is available. Camera traffic will use this route.");refreshNetworks();});}
            @Override public void onUnavailable(){main.post(()->{if(!destroyed)log("WI-FI: join timed out or was declined. Check the camera SSID/password and Android’s connection prompt. No retry was started.");});}
            @Override public void onLost(Network network){if(network.equals(joinedNetwork))joinedNetwork=null;main.post(()->{if(!destroyed){log("WI-FI: camera network lost. Rejoin before testing again.");refreshNetworks();}});}
        };
        connectivity.requestNetwork(request,joinedCallback,60000);
        log("WI-FI: Android connection request opened. Accept the system prompt. Internet access is not required.");
    }
    private void startHotspot(){
        if(hotspot!=null||hotspotStarting){log("HOTSPOT: already active or starting.");return;}
        releaseJoined();hotspotStarting=true;final int generation=++hotspotGeneration;
        try{wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback(){
            @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation){
                if(destroyed||generation!=hotspotGeneration){reservation.close();return;}hotspotStarting=false;hotspot=reservation;
                String name,password;
                if(Build.VERSION.SDK_INT>=30){SoftApConfiguration c=reservation.getSoftApConfiguration();name=c.getSsid();password=c.getPassphrase();}
                else{WifiConfiguration c=reservation.getWifiConfiguration();name=c.SSID;password=c.preSharedKey;}
                hotspotInfo.setText("Phone hotspot is ready\nWi-Fi name: "+name+"\nPassword: "+password+"\nOn the camera: CCAPI → Connect → Add with wizard. Choose this network. Keep the app open.");
                log("HOTSPOT: local network started. Credentials are displayed above and excluded from logs.");refreshNetworks();route.setSelection(0);
            }
            @Override public void onStopped(){if(generation!=hotspotGeneration)return;hotspotStarting=false;hotspot=null;if(!destroyed){hotspotInfo.setText("Phone hotspot stopped.");log("HOTSPOT: stopped");}}
            @Override public void onFailed(int reason){if(generation!=hotspotGeneration)return;hotspotStarting=false;log("HOTSPOT: Android rejected the request (reason "+reason+"). Try turning off an existing hotspot, or enable a hotspot in phone settings and join it from the camera.");}
        },main);}catch(SecurityException e){hotspotStarting=false;failure(e);return;}catch(RuntimeException e){hotspotStarting=false;throw e;}
        log("HOTSPOT: requesting a local-only network. No Internet connection is needed.");
    }
    private void releaseJoined(){
        if(joinedCallback!=null){try{connectivity.unregisterNetworkCallback(joinedCallback);}catch(IllegalArgumentException ignored){}joinedCallback=null;}joinedNetwork=null;
    }
    private void releaseLinks(){
        cancel();releaseJoined();hotspotGeneration++;hotspotStarting=false;if(hotspot!=null){hotspot.close();hotspot=null;}hotspotInfo.setText("Local hotspot is off.");refreshNetworks();
    }
    private void refreshNetworks(){
        if(destroyed)return;
        Network previous=selectedNetwork();routes.clear();routes.add(null);
        ArrayList<String> labels=new ArrayList<>();labels.add("Automatic / phone hotspot");StringBuilder details=new StringBuilder();int selected=0;
        for(Network n:connectivity.getAllNetworks()){
            NetworkCapabilities c=connectivity.getNetworkCapabilities(n);LinkProperties p=connectivity.getLinkProperties(n);
            if(c==null||p==null)continue;
            if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
                routes.add(n);labels.add("Wi-Fi "+p.getInterfaceName()+" ("+n+")");if(n.equals(previous)||n.equals(joinedNetwork))selected=routes.size()-1;
                details.append("Wi-Fi ").append(p.getInterfaceName()).append(": ").append(p.getLinkAddresses()).append("\n");
                for(RouteInfo r:p.getRoutes())if(r.isDefaultRoute()&&r.hasGateway())details.append("Gateway candidate: ").append(r.getGateway().getHostAddress()).append("\n");
            }
            if(c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))details.append("VPN is active; select the camera Wi-Fi route when available.\n");
        }
        if(routes.size()==1)details.append("No selectable Wi-Fi client network. Automatic can still reach a camera on the phone hotspot.\n");
        try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces()))if(ni.isUp()&&!ni.isLoopback())for(InetAddress a:Collections.list(ni.getInetAddresses()))if(a instanceof Inet4Address)details.append(ni.getName()).append(": ").append(a.getHostAddress()).append("\n");}catch(SocketException ignored){}
        route.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));route.setSelection(selected);networkInfo.setText(details);
    }
    private Network selectedNetwork(){if(route==null)return null;int i=route.getSelectedItemPosition();return i>=0&&i<routes.size()?routes.get(i):null;}
    private SocketFactory sockets(Network n){return n==null?SocketFactory.getDefault():n.getSocketFactory();}
    private void runTest(String title,Runnable work){
        if(busy){log("A test is already running. Cancel it first.");return;}
        cancelled=false;busy=true;results.removeAllViews();status.setText(title);progress.setVisibility(View.VISIBLE);taskButtons.forEach(b->b.setEnabled(false));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);log("TEST: "+title);
        task=worker.submit(()->{try{if(!cancelled)work.run();}catch(Exception e){failure(e);}finally{main.post(()->{if(destroyed)return;busy=false;progress.setVisibility(View.GONE);taskButtons.forEach(b->b.setEnabled(true));getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText(cancelled?"Test cancelled":"Test finished. See log for result.");});}});
    }
    private void cancel(){
        if(!busy)return;cancelled=true;if(ptp!=null)ptp.close();if(http!=null)http.close();if(discovery!=null)discovery.close();log("CANCEL: requested; closing active connections.");
    }
    private void failure(Exception error){
        while(error.getCause() instanceof Exception)error=(Exception)error.getCause();
        String message=error.getMessage();if(message==null)message=error.getClass().getSimpleName();
        log((cancelled?"CANCELLED: ":"ERROR: ")+error.getClass().getSimpleName()+": "+message);
        if(error instanceof java.net.SocketTimeoutException)log("NEXT: check that phone and camera share a Wi-Fi link, the camera is in the selected service mode, and the address/port match its screen.");
        if(error instanceof javax.net.ssl.SSLException)log("NEXT: camera reached but HTTPS certificate validation failed. For an isolated connection test, select HTTP in camera CCAPI settings and enter that HTTP URL. No certificate checks were disabled.");
    }
    private void log(String line){
        main.post(()->{if(destroyed)return;String stamp=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());transcript.append(stamp).append("  ").append(line.replaceAll("[\\r\\n]+"," ")).append('\n');
            if(transcript.length()>90000)transcript.delete(0,transcript.length()-75000);logView.setText(transcript);persistLog();});
    }
    private void persistLog(){try(FileOutputStream out=openFileOutput("connection-log.txt",MODE_PRIVATE)){out.write(transcript.toString().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}}
    private void shareLog(){Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_SUBJECT,"Camera Link Lab connection log");intent.putExtra(Intent.EXTRA_TEXT,transcript.toString());startActivity(Intent.createChooser(intent,"Share connection log"));}

    private void browse(){
        final URL url;try{url=CameraHttp.url(cameraUrl.getText().toString());}catch(IOException e){failure(e);return;}
        Network n=selectedNetwork();String user=account.getText().toString(),pass=accountPass.getText().toString();
        getPreferences(0).edit().putString("url",url.toString()).apply();
        runTest("Checking CCAPI",()->{
            log("ROUTE: "+(n==null?"Automatic": "Wi-Fi "+n));
            try(CameraHttp client=new CameraHttp(u->n==null?u.openConnection():n.openConnection(u),user,pass,this::log)){
                http=client;if(cancelled)client.close();
                URL current=url;
                for(int probe=0;probe<2;probe++){
                  HttpURLConnection c=client.get(current);
                  try{
                    String contentType=c.getContentType();log("HTTP: 200. Camera endpoint is reachable; type "+contentType);
                    byte[] body=CameraHttp.readBounded(c.getInputStream(),1024*1024);
                    Object json=new JSONTokener(new String(body,StandardCharsets.UTF_8)).nextValue();
                    if(!(json instanceof JSONObject)&&!(json instanceof JSONArray))throw new IOException("Response is not a CCAPI JSON object or array");
                    if(probe==0 && json instanceof JSONObject && "No list of APIs".equals(((JSONObject)json).optString("value"))
                            && (current.getPath().equals("/ccapi")||current.getPath().equals("/ccapi/"))){
                        log("DISCOVERY: camera returned No list of APIs. Requesting the developer API list on the same camera.");
                        current=CameraHttp.url(new URL(current,"/ccapi/ver100/topurlfordev").toString());continue;
                    }
                    LinkedHashSet<String> links=new LinkedHashSet<>();CcapiLinks.collect(json,links);
                    log("CCAPI: JSON received, "+body.length+" bytes, "+links.size()+" navigable links. This confirms HTTP/API access, not yet complete file coverage.");
                    String formatted=json instanceof JSONObject?((JSONObject)json).toString(2):((JSONArray)json).toString(2);
                    URL responseUrl=current;
                    main.post(()->showResponse(responseUrl,formatted,links,n));
                    break;
                  }finally{c.disconnect();}
                }
            }catch(JSONException e){throw new RuntimeException("HTTP endpoint replied, but JSON parsing failed. Check that this is the CCAPI URL.",e);}catch(IOException e){throw new RuntimeException(e.getMessage(),e);}finally{http=null;}
        });
    }
    private void showResponse(URL base,String json,Set<String> links,Network n){
        if(destroyed||cancelled)return;results.removeAllViews();text(results,"Camera response",19,INK,true);
        text(results,"Tap a returned link to inspect it. For a media file, choose Save original. Listing pages are not followed automatically.",14,MUTED,false);
        int shown=0;
        for(String link:links){try{
            URL u=CameraHttp.url(new URL(base,link).toString());if(!CameraHttp.sameOrigin(base,u))continue;
            if(shown++>=150)break;
            String path=u.getPath()+(u.getQuery()==null?"":"?"+u.getQuery());
            button(results,path,()->{
                if(busy)return;
                boolean media=u.getPath().matches("(?i).*\\.(cr3|cr2|jpg|jpeg|hif|heif|mp4|mov|wav|xml)$");
                if(media)new AlertDialog.Builder(this).setTitle("Camera file").setMessage(u.getPath()).setPositiveButton("Save original",(d,w)->chooseDownload(u,n)).setNegativeButton("Cancel",null).show();
                else{cameraUrl.setText(u.toString());browse();}
            });
        }catch(IOException ignored){} }if(shown>150)text(results,"Showing the first 150 links. Use the camera’s returned pagination parameters to open additional listing pages.",14,MUTED,false);
        TextView raw=text(results,json.length()>18000?json.substring(0,18000)+"\n[Display truncated]":json,12,INK,false);raw.setTypeface(Typeface.MONOSPACE);raw.setTextIsSelectable(true);
    }
    private void chooseDownload(URL url,Network n){
        pendingDownload=url;pendingNetwork=n;
        String filename=Uri.decode(url.getPath().substring(url.getPath().lastIndexOf('/')+1));
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,filename);
        startActivityForResult(intent,20);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=20||result!=RESULT_OK||data==null||pendingDownload==null)return;
        URL url=pendingDownload;Network n=pendingNetwork;Uri destination=data.getData();pendingDownload=null;
        String user=account.getText().toString(),pass=accountPass.getText().toString();
        runTest("Saving original file",()->{
            boolean complete=false;
            try(CameraHttp client=new CameraHttp(u->n==null?u.openConnection():n.openConnection(u),user,pass,this::log)){
                http=client;if(cancelled)client.close();HttpURLConnection c=client.get(url);
                try{
                    long expected=c.getContentLengthLong(),count=0,last=System.nanoTime();MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    try(InputStream in=c.getInputStream();OutputStream out=getContentResolver().openOutputStream(destination,"w")){
                        if(out==null)throw new IOException("Destination is not writable");byte[] buffer=new byte[65536];int read;
                        while((read=in.read(buffer))!=-1){if(cancelled)throw new IOException("Cancelled");out.write(buffer,0,read);digest.update(buffer,0,read);count+=read;
                            if(System.nanoTime()-last>2_000_000_000L){log("DOWNLOAD: "+count+" / "+(expected<0?"unknown":expected)+" bytes");last=System.nanoTime();}}
                    }
                    if(expected>=0&&count!=expected)throw new IOException("Incomplete download: "+count+" of "+expected+" bytes");
                    complete=true;log("SAVED: "+count+" bytes. SHA-256 "+CameraHttp.hex(digest.digest()));
                    log("Compare this hash with a source copy to establish byte-for-byte fidelity. Nothing was deleted from the camera.");
                }finally{c.disconnect();}
            }catch(Exception e){throw new RuntimeException(e.getMessage(),e);}finally{http=null;if(!complete){try{android.provider.DocumentsContract.deleteDocument(getContentResolver(),destination);log("Incomplete destination removed.");}catch(Exception e){log("Remove the incomplete destination manually before retrying.");}}}
        });
    }

    private void testPtp(){
        String host=cameraHost.getText().toString().trim();
        if(host.isEmpty()||host.contains("://")||host.contains("/")){log("Enter only the camera IP address for the EOS Utility test.");return;}
        Network n=selectedNetwork();getPreferences(0).edit().putString("host",host).apply();
        String guidString=getPreferences(0).getString("guid",null);
        if(guidString==null){guidString=UUID.randomUUID().toString();getPreferences(0).edit().putString("guid",guidString).apply();}
        UUID id=UUID.fromString(guidString);byte[] guid=java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        runTest("Testing EOS Utility pairing",()->{
            PtpIp client=new PtpIp(sockets(n),this::log);ptp=client;if(cancelled)client.close();
            try{log("ROUTE: "+(n==null?"Automatic":"Wi-Fi "+n));client.connect(host,15740,guid);client.diagnose();}
            catch(IOException e){throw new RuntimeException(e.getMessage(),e);}finally{client.finish();ptp=null;}
        });
    }
    private void discover(){
        Network n=selectedNetwork();runTest("Discovering EOS camera",()->{
            WifiManager.MulticastLock lock=wifi.createMulticastLock("camera-link-discovery");lock.setReferenceCounted(false);
            LinkedHashSet<String> found=new LinkedHashSet<>();
            try(DatagramSocket socket=new DatagramSocket(null)){
                discovery=socket;socket.setReuseAddress(true);socket.bind(new InetSocketAddress(0));if(n!=null)n.bindSocket(socket);socket.setSoTimeout(800);lock.acquire();
                for(String target:new String[]{SERVICE,"ssdp:all"}){
                    String query="M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "+target+"\r\n\r\n";
                    byte[] bytes=query.getBytes(StandardCharsets.US_ASCII);socket.send(new DatagramPacket(bytes,bytes.length,InetAddress.getByName("239.255.255.250"),1900));
                }
                log("DISCOVERY: SSDP search sent; listening for 7 seconds.");long end=System.nanoTime()+7_000_000_000L;
                while(!cancelled&&System.nanoTime()<end){
                    try{byte[] buffer=new byte[8192];DatagramPacket packet=new DatagramPacket(buffer,buffer.length);socket.receive(packet);String response=new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8);
                        if(response.contains(SERVICE)||response.toLowerCase(Locale.ROOT).contains("canon")){
                            String address=packet.getAddress().getHostAddress();if(found.add(address))log("DISCOVERED: Canon service at "+address);
                        }
                    }catch(SocketTimeoutException ignored){}
                }
                if(found.isEmpty())log("DISCOVERY: no Canon reply. This does not prove Wi-Fi failed. Enter the camera IP manually; multicast may be blocked, particularly on a hotspot or VPN.");
                else main.post(()->{if(destroyed||cancelled)return;String[] addresses=found.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Choose camera address").setItems(addresses,(d,w)->cameraHost.setText(addresses[w])).show();});
            }catch(IOException e){throw new RuntimeException(e.getMessage(),e);}finally{discovery=null;if(lock.isHeld())lock.release();}
        });
    }
    @Override protected void onDestroy(){
        destroyed=true;cancel();worker.shutdownNow();releaseJoined();if(hotspot!=null)hotspot.close();super.onDestroy();
    }
}
