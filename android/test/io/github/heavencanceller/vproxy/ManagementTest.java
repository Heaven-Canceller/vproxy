package io.github.heavencanceller.vproxy;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Exercises the actual Android SQLite/JNI transport using local subscription fixtures. */
public final class ManagementTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private Object call(String method,String path,JSONObject body)throws Exception{return CoreClient.call(getTargetContext(),method,path,body);}
    private JSONObject object(String method,String path,JSONObject body)throws Exception{return (JSONObject)call(method,path,body);}
    @Override public void onStart(){
        Bundle result=new Bundle();String sourceId=null;
        try{
            object("GET","/android/init",null);
            JSONObject legacy=object("GET","/api/nodes?keyword=LegacyV1&page_size=50",null);
            require(legacy.getInt("total")==1,"v1 node migration");
            require(new File(getTargetContext().getFilesDir(),"nodes.json").isFile(),"v1 file preserved");
            call("POST","/android/import",new JSONObject().put("content","ss://YWVzLTEyOC1nY206cGFzc3dvcmQ=@1.2.3.4:8388#V2Manual"));
            JSONObject manual=object("GET","/api/nodes?keyword=V2Manual&page_size=50",null);
            require(manual.getInt("total")==1,"node import and filtering");
            String nodeId=manual.getJSONArray("items").getJSONObject(0).getString("id");
            call("POST","/android/import",new JSONObject().put("content","ss://YWVzLTEyOC1nY206cGFzc3dvcmQ=@1.2.3.4:8388#V2Manual"));
            require(object("GET","/api/nodes?keyword=V2Manual",null).getInt("total")==1,"import deduplication");
            try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
                server.setSoTimeout(20000);
                Thread fixture=new Thread(()->{try(Socket socket=server.accept()){
                    BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                    byte[] data="ss://YWVzLTEyOC1nY206cGFzc3dvcmQ=@8.8.4.4:8389#SubscriptionFixture".getBytes(StandardCharsets.UTF_8);
                    OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+data.length+"\r\nSubscription-Userinfo: upload=1; download=2; total=100\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
                }catch(Exception error){android.util.Log.e("VProxyV2Test","subscription fixture failed",error);}},"subscription-fixture");fixture.start();
                JSONObject source=object("POST","/api/sources",new JSONObject().put("name","V2 test source").put("url_template","http://127.0.0.1:"+server.getLocalPort()+"/subscription").put("enabled",true));
                sourceId=source.getString("id");
                JSONObject fetched=object("POST","/api/sources/"+sourceId+"/fetch",new JSONObject());
                require(fetched.optBoolean("success"),"source fetch: "+fetched);
                require(object("GET","/api/nodes?source_id="+sourceId,null).getInt("total")==1,"source shard persistence");
                fixture.join(1000);
            }
            call("PUT","/api/sources/"+sourceId,new JSONObject().put("enabled",false).put("name","Renamed source"));
            require(!object("GET","/api/sources/"+sourceId,null).getBoolean("enabled"),"source edit and disable");
            call("POST","/android/rules",new JSONObject().put("text","DOMAIN-SUFFIX,example.com,DIRECT\nDST-PORT,25,REJECT"));
            require(object("GET","/android/rules",null).getString("text").contains("example.com"),"rules persisted");
            JSONObject options=object("GET","/api/proxy/config",null);
            options.put("routing_mode","rule").put("override_yaml","log-level: debug");
            call("PUT","/api/proxy/config",options);
            JSONObject compiled=object("POST","/android/compile",new JSONObject().put("node_id",nodeId).put("kernel","mihomo")).getJSONObject("config");
            JSONObject normalized=new JSONObject(NativeCore.engineConfig(new JSONObject(compiled.toString())));
            require(normalized.getJSONArray("rules").toString().contains("DOMAIN-SUFFIX,example.com,DIRECT"),"rules survive Android normalization");
            require(normalized.getString("log-level").equals("debug"),"YAML override applied");
            require(normalized.getString("secret").length()>20,"random controller secret");
            options.put("routing_mode","global").put("override_yaml",JSONObject.NULL);call("PUT","/api/proxy/config",options);
            JSONObject backup=object("GET","/android/backup",null);
            int before=object("GET","/api/stats",null).getInt("node_count");
            call("POST","/android/restore",backup);call("POST","/android/restore",backup);
            require(object("GET","/api/stats",null).getInt("node_count")==before,"idempotent backup merge and grouping");
            require(object("GET","/api/nodes?source_id="+sourceId,null).getInt("total")==1,"restore source mapping");
            require(object("GET","/api/nodes/"+nodeId,null).getString("id").equals(nodeId),"node identity survives restore");
            call("POST","/android/health",new JSONObject().put("ok",true).put("latency_ms",123).put("node_id",nodeId));
            require(object("GET","/android/history",null).getJSONArray("health").length()>0,"health history persistence");
            JSONArray kernels=(JSONArray)call("GET","/api/kernels",null);boolean packaged=false;
            for(int i=0;i<kernels.length();i++)if(kernels.getJSONObject(i).optString("kind").equals("mihomo"))packaged=kernels.getJSONObject(i).getBoolean("available");
            require(packaged,"APK engine detection");
            require(object("POST","/android/cancel",new JSONObject()).getBoolean("cancelled"),"cancellation request");
            try(LocalApiServer api=new LocalApiServer(getTargetContext())){
                require(apiStatus(null)==401,"management API rejects unauthenticated request");
                require(apiStatus(LocalApiServer.token(getTargetContext()))==200,"management API authenticated stats");
            }
            call("DELETE","/api/sources/"+sourceId,null);sourceId=null;
            call("DELETE","/api/nodes/"+nodeId,null);
            final MainActivity activity=(MainActivity)startActivitySync(new android.content.Intent(getTargetContext(),MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(500);
            String[] pages={"概览","订阅","节点","策略","监控","设置"};String[] headings={"连接与健康","订阅源","节点池","路由、DNS 与配置","运行监控","后台与设置"};
            for(int i=0;i<pages.length;i++){
                final String page=pages[i];runOnMainSync(()->activity.showPage(page));Thread.sleep(350);
                final int index=i;final boolean[] visible={false};runOnMainSync(()->visible[0]=hasText(activity.getWindow().getDecorView(),headings[index]));require(visible[0],"page rendered: "+page);
                android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
                if(screenshot!=null){try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"v2-screen-"+i+".png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
            }
            runOnMainSync(()->activity.showPage("概览"));
            result.putString("stream","PASS: v1 migration, source CRUD and local HTTP fetch, node merge/filter, rules and YAML, backup idempotence, history, packaged engine, authenticated API, cancellation, six UI pages\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream","FAIL: "+error+"\n"+android.util.Log.getStackTraceString(error));finish(0,result);}
    }
    private int apiStatus(String token)throws Exception{
        try(Socket socket=new Socket("127.0.0.1",18080)){
            socket.setSoTimeout(10000);String auth=token==null?"":"Authorization: Bearer "+token+"\r\n";
            socket.getOutputStream().write(("GET /api/stats HTTP/1.1\r\nHost: localhost\r\n"+auth+"Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String first=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();return Integer.parseInt(first.split(" ")[1]);
        }
    }
    private static void require(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    private static boolean hasText(android.view.View view,String label){
        if(view instanceof android.widget.TextView&&((android.widget.TextView)view).getText().toString().equals(label))return true;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),label))return true;}
        return false;
    }
}
