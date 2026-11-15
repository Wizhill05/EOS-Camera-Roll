package dev.aryan.cameralinklab;

import java.net.*;
import javax.net.ssl.SSLException;

final class LibraryIssue {
    final String title,detail;
    LibraryIssue(String title,String detail){this.title=title;this.detail=detail;}
    static LibraryIssue from(Throwable error){
        for(Throwable e=error;e!=null;e=e.getCause()){
            if(e instanceof UnknownHostException)return new LibraryIssue("Device not found","Check the camera address, then refresh. The camera may have a new address after reconnecting.");
            if(e instanceof ConnectException||e instanceof NoRouteToHostException||e instanceof SocketTimeoutException||e instanceof SocketException)return new LibraryIssue("Camera out of reach","Turn on the camera and CCAPI, connect it to this phone's hotspot or join its Wi-Fi, then refresh.");
            if(e instanceof SSLException)return new LibraryIssue("Camera certificate not trusted","Use the working HTTP camera address, or configure trust for the camera's HTTPS certificate.");
            if(e instanceof CameraHttp.StatusException){int status=((CameraHttp.StatusException)e).status;
                if(status==403)return new LibraryIssue("Camera refused access","The camera is reachable. Check CCAPI mode and account settings on the camera, then reconnect.");
                if(status==404)return new LibraryIssue("Camera API not found","The device replied, but that camera endpoint is unavailable. Check the address shown by CCAPI.");
                if(status>=500)return new LibraryIssue("Camera is busy","The camera replied with an error. Let it finish its current operation, then refresh.");
            }
            String message=e.getMessage();if(message!=null&&message.contains("401"))return new LibraryIssue("Camera sign-in required","The camera is reachable. Open connection settings and check the CCAPI username and password.");
        }
        return new LibraryIssue("Could not read the library","Check the connection and refresh. Your cached library has been kept. Connection details are available in the log.");
    }
}
