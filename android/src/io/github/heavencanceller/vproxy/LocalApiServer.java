package io.github.heavencanceller.vproxy;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.*;

/** Optional authenticated loopback API, owned by the VPN service lifetime. */
final class LocalApiServer implements AutoCloseable {
    private final Context context;
    private final ServerSocket server;
    private final ExecutorService clients=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(4));
    static synchronized String token(Context context){
        android.content.SharedPreferences prefs=context.getSharedPreferences("MainActivity",0);
        String token=prefs.getString("android_api_token","");
        if(token.isEmpty()){token=java.util.UUID.randomUUID().toString()+java.util.UUID.randomUUID();prefs.edit().putString("android_api_token",token).apply();}
        return token;
    }
    LocalApiServer(Context context)throws Exception{
        this.context=context.getApplicationContext();
        server=new ServerSocket(18080,4,InetAddress.getByName("127.0.0.1"));
        new Thread(()->{while(!server.isClosed()){try{Socket socket=server.accept();try{clients.execute(()->serve(socket));}catch(RejectedExecutionException error){socket.close();}}catch(Exception error){if(server.isClosed())break;}}},"vproxy-management-api").start();
    }
    private static String line(InputStream input)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();int b;
        while((b=input.read())!=-1){if(b=='\n')break;if(b!='\r')output.write(b);if(output.size()>8192)throw new IOException("Header too large");}
        return output.toString("US-ASCII");
    }
    private void serve(Socket socket){
        try(Socket client=socket){
            client.setSoTimeout(5000);InputStream input=client.getInputStream();String[] first=line(input).split(" ");
            if(first.length!=3)return;
            String auth="";int size=0,total=0;boolean chunked=false;
            for(String header;(header=line(input))!=null&&!header.isEmpty();){total+=header.length();if(total>16384)throw new IOException("Headers too large");int colon=header.indexOf(':');if(colon<0)continue;String key=header.substring(0,colon).trim(),value=header.substring(colon+1).trim();if(key.equalsIgnoreCase("Authorization"))auth=value;if(key.equalsIgnoreCase("Content-Length"))size=Integer.parseInt(value);if(key.equalsIgnoreCase("Transfer-Encoding"))chunked=true;}
            if(!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),("Bearer "+token(context)).getBytes(StandardCharsets.UTF_8))){
                respond(client,401,new JSONObject().put("ok",false).put("message","Unauthorized"));return;
            }
            if(chunked||size<0||size>2*1024*1024){respond(client,400,new JSONObject().put("ok",false).put("message","Unsupported body"));return;}
            byte[] body=new byte[size];new DataInputStream(input).readFully(body);
            String method=first[0],path=first[1];
            if(!path.startsWith("/api/")||path.contains("://")){respond(client,404,new JSONObject().put("ok",false));return;}
            JSONObject payload=size==0?null:new JSONObject(new String(body,StandardCharsets.UTF_8));
            Object value;
            if(path.equals("/api/proxy/status"))value=new JSONObject().put("running",ProxyVpnService.running).put("message",ProxyVpnService.status);
            else if(path.equals("/api/proxy/stop")&&method.equals("POST")){context.startService(new android.content.Intent(context,ProxyVpnService.class).setAction("stop"));value=new JSONObject().put("stopping",true);}
            else value=CoreClient.call(context,method,path,payload);
            respond(client,200,new JSONObject().put("ok",true).put("data",value));
        }catch(Exception ignored){}
    }
    private static void respond(Socket client,int code,JSONObject data)throws Exception{
        byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream output=client.getOutputStream();output.write(("HTTP/1.1 "+code+" "+(code==200?"OK":"Error")+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
    }
    @Override public void close(){try{server.close();}catch(IOException ignored){}clients.shutdownNow();}
}
