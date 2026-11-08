package io.github.heavencanceller.vproxy;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
    private final ExecutorService worker=Executors.newFixedThreadPool(3);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status,traffic;
    private TrafficChart chart;
    private String page="概览", selectedId="", selectedName="", exportText, pendingConfig;
    private int nodePage=1, tasksRunning;
    private String keyword="", nodeSort="latency", latencyFilter="all";
    private TextView nodeHeading;
    private long lastDownload,lastSample;
    private boolean monitorBusy;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            status.setText(ProxyVpnService.status+(tasksRunning>0?" · "+tasksRunning+" 个任务执行中":""));
            if(page.equals("概览") && ProxyVpnService.running && !monitorBusy && chart!=null){
                monitorBusy=true;
                worker.execute(()->{
                    try{
                        JSONObject c=ControllerClient.request("GET","/connections",null);
                        long down=c.optLong("downloadTotal"),up=c.optLong("uploadTotal"),now=SystemClock.elapsedRealtime();
                        float speed=lastSample==0?0:Math.max(0,down-lastDownload)*1000f/Math.max(1,now-lastSample);
                        lastDownload=down;lastSample=now;
                        handler.post(()->{if(page.equals("概览")&&chart!=null){chart.sample(speed);traffic.setText("↓ "+bytes(down)+"   ↑ "+bytes(up)+"   当前下载 "+bytes((long)speed)+"/s");}});
                    }catch(Exception ignored){}finally{monitorBusy=false;}
                });
            }
            handler.postDelayed(this,1500);
        }
    };

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setNavigationBarColor(Color.rgb(245,247,251));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(245,247,251));
        LinearLayout header=new LinearLayout(this);header.setPadding(dp(18),dp(28),dp(18),dp(8));header.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("VProxy  v2",27,Color.rgb(24,40,65));title.setTypeface(null,1);header.addView(title);
        status=text("正在打开节点数据库…",13,Color.rgb(20,100,100));header.addView(status);root.addView(header);
        HorizontalScrollView navigation=new HorizontalScrollView(this);navigation.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs=row();for(String name:new String[]{"概览","订阅","节点","策略","监控","设置"}) {Button tab=new Button(this);tab.setText(name);tab.setTextSize(13);tab.setOnClickListener(v->showPage(name));tabs.addView(tab,new LinearLayout.LayoutParams(dp(75),-2));}
        navigation.addView(tabs);root.addView(navigation);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(10),dp(18),dp(30));
        scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        selectedId=prefs().getString("selectedId","");selectedName=prefs().getString("selectedName","");
        work("初始化数据库与 v1 数据迁移",()->{
            CoreClient.call(this,"GET","/android/init",null);
            if(!prefs().getBoolean("v2_selected_migrated",false)){
                File legacy=new File(getFilesDir(),"nodes.json");
                if(legacy.isFile()){
                    JSONArray old=new JSONArray(new String(Files.readAllBytes(legacy.toPath()),StandardCharsets.UTF_8));
                    int index=prefs().getInt("selected",0);
                    if(index>=0&&index<old.length())select(old.getJSONObject(index));
                }
                prefs().edit().putBoolean("v2_selected_migrated",true).apply();
            }
            return CoreClient.call(this,"GET","/api/settings",null);
        },value->{JSONObject s=(JSONObject)value;RefreshJobService.schedule(this,s.optBoolean("auto_fetch_enabled"),s.optInt("auto_fetch_interval_min",60));showPage(page);});
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},20);
    }
    private SharedPreferences prefs(){return getSharedPreferences("MainActivity",MODE_PRIVATE);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private static String bytes(long n){return n>1048576?String.format(Locale.ROOT,"%.2f MiB",n/1048576d):String.format(Locale.ROOT,"%.1f KiB",n/1024d);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private void heading(String label){TextView v=text(label,20,Color.rgb(24,40,65));v.setPadding(0,dp(15),0,dp(8));content.addView(v);}
    private void hint(String label){TextView v=text(label,13,Color.rgb(75,94,121));v.setPadding(0,dp(6),0,dp(8));content.addView(v);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    private void action(String label,Runnable action){LinearLayout l=row();button(l,label,action);content.addView(l);}
    private void actions(String a,Runnable aa,String b,Runnable bb){LinearLayout l=row();button(l,a,aa);button(l,b,bb);content.addView(l);}
    private EditText field(LinearLayout parent,String label,String value,int lines){
        parent.addView(text(label,13,Color.rgb(75,94,121)));
        EditText e=new EditText(this);e.setText(value);e.setTextSize(14);e.setMinLines(lines);e.setMaxLines(Math.max(lines,6));
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
    }
    private CheckBox check(LinearLayout parent,String label,boolean value){CheckBox c=new CheckBox(this);c.setText(label);c.setChecked(value);parent.addView(c);return c;}
    private LinearLayout form(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(18),dp(5),dp(18),dp(8));return l;}
    private void dialog(String title,String message){TextView v=text(message,13,Color.DKGRAY);v.setTextIsSelectable(true);v.setPadding(dp(16),dp(10),dp(16),dp(10));ScrollView s=new ScrollView(this);s.addView(v);new AlertDialog.Builder(this).setTitle(title).setView(s).setPositiveButton("关闭",null).show();}
    private void confirm(String message,Runnable action){new AlertDialog.Builder(this).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确定",(d,w)->action.run()).show();}
    interface Task{Object run()throws Exception;}
    private void work(String label,Task task,java.util.function.Consumer<Object> done){
        tasksRunning++;status.setText(label+"…");worker.execute(()->{
            try{Object value=task.run();handler.post(()->{tasksRunning--;if(!isDestroyed())done.accept(value);});}
            catch(Throwable error){handler.post(()->{tasksRunning--;if(!isDestroyed())dialog("操作失败",label+"："+error.getMessage());});}
        });
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void select(JSONObject node){selectedId=node.optString("id");selectedName=node.optString("name");prefs().edit().putString("selectedId",selectedId).putString("selectedName",selectedName).apply();}
    void showPage(String name){page=name;content.removeAllViews();chart=null;traffic=null;
        switch(name){case "订阅":sources();break;case "节点":nodes();break;case "策略":policies();break;case "监控":monitor();break;case "设置":settings();break;default:overview();}
    }
    private void overview(){
        heading("连接与健康");hint("当前节点："+(selectedName.isEmpty()?"尚未选择":selectedName));
        actions(ProxyVpnService.running?"断开 VPN":"连接 VPN",this::toggleVpn,"切换节点",()->showPage("节点"));
        traffic=text("连接后显示内核累计流量及速率",14,Color.rgb(75,94,121));content.addView(traffic);
        chart=new TrafficChart(this);content.addView(chart,new LinearLayout.LayoutParams(-1,dp(110)));
        actions("实际代理健康检查",this::healthCheck,"查看健康历史",()->work("读取历史",()->CoreClient.call(this,"GET","/android/history",null),v->dialog("健康与拉取记录",pretty(v))));
        heading("节点与订阅");TextView stats=text("正在读取…",15,Color.DKGRAY);content.addView(stats);
        work("读取统计",()->CoreClient.object(this,"GET","/api/stats",null),v->{JSONObject s=(JSONObject)v;stats.setText("订阅 "+s.optInt("source_count")+" · 节点 "+s.optInt("node_count")+" · 已测 "+s.optInt("tested_count")+" · 探测成功 "+s.optInt("alive_count"));});
        actions("导入节点",this::importDialog,"从文件导入",()->openDocument(12));
        hint("v2 使用桌面 Rust 数据库和业务接口。TCP 测速、内核真实验证和连接后的健康检查分别记录；实际可用性取决于你的节点。");
    }
    private void importDialog(){LinearLayout l=form();EditText e=field(l,"节点链接 / Base64 / Clash YAML","",5);new AlertDialog.Builder(this).setTitle("导入并合并节点").setView(l).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->importText(e.getText().toString())).show();}
    private void importText(String value){work("解析并合并节点",()->CoreClient.object(this,"POST","/android/import",new JSONObject().put("content",value)),v->{toast("已处理 "+((JSONObject)v).optInt("imported")+" 个节点");showPage("节点");});}
    private void sources(){
        heading("订阅源");actions("新增订阅",()->editSource(null),"拉取已启用源",()->work("拉取订阅",()->CoreClient.call(this,"POST","/api/sources/fetch-all",new JSONObject()),v->{dialog("拉取结果",pretty(v));showPage("订阅");}));
        actions("Gist 发现",this::discoverGists,"手动导入",this::importDialog);
        actions("OpenRung 中继",()->confirm("添加 OpenRung 官方订阅源？添加后可手动拉取。",()->work("添加 OpenRung",()->CoreClient.call(this,"POST","/api/sources",new JSONObject().put("name","OpenRung").put("url_template","https://broker.openrung.org/api/v1/relays").put("enabled",true)),v->showPage("订阅"))),"注册 WARP",()->confirm("向 Cloudflare 注册 WARP 设备并获取 MASQUE 节点？此操作会发送注册请求。",()->work("注册 WARP",()->CoreClient.call(this,"POST","/android/warp",new JSONObject()),v->{dialog("WARP 注册",pretty(v));showPage("订阅");})));
        action("订阅源重复度分析",()->work("比较来源节点",()->CoreClient.call(this,"GET","/android/homogeneity",null),v->dialog("来源重复度",pretty(v))));
        hint("点按订阅可更新、编辑、启停或删除。支持 HTTPS 订阅、日期占位符和 Telegram 公开频道 URL。");
        LinearLayout list=new LinearLayout(this);list.setOrientation(1);content.addView(list);
        work("读取订阅源",()->CoreClient.call(this,"GET","/api/sources",null),v->{JSONArray all=(JSONArray)v;for(int i=0;i<all.length();i++){JSONObject source=all.optJSONObject(i);TextView item=text((source.optBoolean("enabled")?"● ":"○ ")+source.optString("name")+"\n节点 "+source.optInt("last_node_count")+" · "+(source.isNull("last_status")?"尚未拉取":source.optString("last_status")),15,Color.rgb(24,40,65));item.setPadding(dp(8),dp(14),dp(8),dp(14));item.setOnClickListener(x->sourceActions(source));list.addView(item);}});
    }
    private void sourceActions(JSONObject source){String id=source.optString("id");new AlertDialog.Builder(this).setTitle(source.optString("name")).setItems(new String[]{"立即拉取","编辑","切换启用状态","删除订阅与其节点","查看详情"},(d,n)->{
        switch(n){case 0:work("拉取源",()->CoreClient.call(this,"POST","/api/sources/"+id+"/fetch",new JSONObject()),v->{dialog("拉取结果",pretty(v));showPage("订阅");});break;
        case 1:editSource(source);break;case 2:work("更新订阅",()->CoreClient.call(this,"PUT","/api/sources/"+id,new JSONObject().put("enabled",!source.optBoolean("enabled"))),v->showPage("订阅"));break;
        case 3:confirm("删除该源及其节点？本地导入源会清空其节点。",()->work("删除订阅",()->CoreClient.call(this,"DELETE","/api/sources/"+id,null),v->showPage("订阅")));break;
        default:dialog("订阅详情",pretty(source));}
    }).show();}
    private void editSource(JSONObject old){
        LinearLayout l=form();EditText name=field(l,"名称",old==null?"":old.optString("name"),1),url=field(l,"订阅 URL / Telegram 频道",old==null?"":old.optString("url_template"),2),ua=field(l,"User-Agent（可留空）",old==null?"":(old.isNull("user_agent")?"":old.optString("user_agent")),1);
        CheckBox enabled=check(l,"启用",old==null||old.optBoolean("enabled"));
        new AlertDialog.Builder(this).setTitle(old==null?"新增订阅":"编辑订阅").setView(l).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->work("保存订阅",()->{
            if(name.getText().toString().trim().isEmpty()||url.getText().toString().trim().isEmpty())throw new Exception("名称和 URL 不能为空");
            JSONObject input=new JSONObject().put("name",name.getText().toString().trim()).put("url_template",url.getText().toString().trim()).put("user_agent",ua.getText().toString().trim()).put("enabled",enabled.isChecked());
            return CoreClient.call(this,old==null?"POST":"PUT","/api/sources"+(old==null?"":"/"+old.optString("id")),input);
        },v->showPage("订阅"))).show();
    }
    private void discoverGists(){LinearLayout l=form();EditText query=field(l,"搜索关键词","vless://",1),pages=field(l,"最多页数（建议先用 1–3）","2",1);new AlertDialog.Builder(this).setTitle("手动发现公开 Gist").setView(l).setNegativeButton("取消",null).setPositiveButton("开始",(d,w)->work("启动发现任务",()->CoreClient.call(this,"POST","/api/gist/discover",new JSONObject().put("query",query.getText().toString()).put("max_pages",Integer.parseInt(pages.getText().toString()))),v->toast("发现任务已启动，可在监控中查看任务进度"))).show();}
    private void nodes(){
        heading("节点池");LinearLayout search=row();EditText input=new EditText(this);input.setHint("搜索名称、服务器或协议");input.setText(keyword);input.setTextSize(14);search.addView(input,new LinearLayout.LayoutParams(0,-2,3));button(search,"搜索",()->{keyword=input.getText().toString();nodePage=1;showPage("节点");});content.addView(search);
        actions("筛选 / 排序",this::nodeFilters,"导入节点",this::importDialog);
        actions("批量内核测速",()->confirm("对节点池发起内核真实代理验证？这会连接节点及测试站点。",()->testNodes(null)),"取消测速",()->work("取消测速",()->CoreClient.call(this,"POST","/android/cancel",new JSONObject()),v->toast("已请求取消")));
        nodeHeading=text("读取节点…",13,Color.GRAY);content.addView(nodeHeading);
        LinearLayout list=new LinearLayout(this);list.setOrientation(1);content.addView(list);
        String uri="/api/nodes?page="+nodePage+"&page_size=50&sort="+nodeSort+"&latency="+latencyFilter+"&keyword="+CoreClient.encode(keyword);
        work("筛选节点",()->CoreClient.object(this,"GET",uri,null),v->{JSONObject result=(JSONObject)v;nodeHeading.setText("共 "+result.optInt("total")+" 个 · 第 "+nodePage+" 页 · 当前选择 "+selectedName);JSONArray all=result.optJSONArray("items");if(all==null)return;
            for(int i=0;i<all.length();i++){JSONObject node=all.optJSONObject(i);String delay=node.isNull("latency_ms")?"未测":node.optLong("latency_ms")+" ms";TextView item=text((selectedId.equals(node.optString("id"))?"✓ ":"")+node.optString("name")+"\n"+node.optString("protocol")+" · "+node.optString("server")+":"+node.optInt("port")+" · "+delay+" · "+(node.isNull("latency_test_kind")?"":node.optString("latency_test_kind")),14,Color.rgb(24,40,65));item.setPadding(dp(6),dp(12),dp(6),dp(12));item.setOnClickListener(x->nodeActions(node));list.addView(item);}
        });
        actions("上一页",()->{nodePage=Math.max(1,nodePage-1);showPage("节点");},"下一页",()->{nodePage++;showPage("节点");});
        actions("去重",()->work("去重",()->CoreClient.call(this,"POST","/api/nodes/dedupe",new JSONObject()),v->{dialog("去重结果",pretty(v));showPage("节点");}),"清理失败节点",()->confirm("删除已标记失败的节点？",()->work("清理节点",()->CoreClient.call(this,"POST","/api/nodes/delete-dead",new JSONObject()),v->showPage("节点"))));
    }
    private void nodeFilters(){new AlertDialog.Builder(this).setTitle("筛选 / 排序").setItems(new String[]{"全部节点","可用节点","失败节点","未测速","按延迟排序","按名称排序","按下载速度排序"},(d,n)->{if(n<4)latencyFilter=new String[]{"all","alive","dead","untested"}[n];else nodeSort=new String[]{"latency","name","download"}[n-4];nodePage=1;showPage("节点");}).show();}
    private void nodeActions(JSONObject node){String id=node.optString("id");new AlertDialog.Builder(this).setTitle(node.optString("name")).setItems(new String[]{"选为连接节点","内核真实测速","TCP 握手测速","详情 / 单节点导出","删除节点"},(d,n)->{
        if(n==0){select(node);showPage("概览");}else if(n==1)testNodes(id);else if(n==2)tcpTest(node);else if(n==3)work("读取节点",()->CoreClient.call(this,"GET","/api/nodes/"+id,null),v->new AlertDialog.Builder(this).setTitle("节点详情").setItems(new String[]{"显示节点 JSON","导出 Mihomo","导出 sing-box","导出 Xray","导出 VP-Core / Meow / Shoes"},(dd,k)->{
            if(k==0)dialog("节点详情（包含凭据）",pretty(v));else if(k==4)chooseExtraCompiler((JSONObject)v);else exportNode((JSONObject)v,new String[]{"","mihomo","sing_box","xray"}[k]);}).show());
        else confirm("删除节点 "+node.optString("name")+"？",()->work("删除节点",()->CoreClient.call(this,"DELETE","/api/nodes/"+id,null),v->showPage("节点")));
    }).show();}
    private void chooseExtraCompiler(JSONObject node){new AlertDialog.Builder(this).setTitle("导出内核").setItems(new String[]{"VP-Core","Meow","Shoes"},(d,n)->exportNode(node,new String[]{"vp_core","meow","shoes"}[n])).show();}
    private void exportNode(JSONObject node,String kernel){work("编译配置",()->CoreClient.object(this,"POST","/android/compile",new JSONObject().put("node_id",node.getString("id")).put("kernel",kernel)),v->saveDocument("vproxy-"+kernel+".json",pretty(((JSONObject)v).opt("config"))));}
    private void tcpTest(JSONObject node){work("TCP 握手测速",()->{long start=SystemClock.elapsedRealtime();boolean ok=false;long elapsed=0;
        try(Socket socket=new Socket(Proxy.NO_PROXY)){socket.connect(new InetSocketAddress(node.getString("server"),node.getInt("port")),4000);ok=true;elapsed=SystemClock.elapsedRealtime()-start;}
        finally{CoreClient.call(this,"POST","/android/tcp-result",new JSONObject().put("id",node.getString("id")).put("ok",ok).put("latency_ms",ok?elapsed:JSONObject.NULL));}
        return elapsed;},v->{dialog("TCP 握手",v+" ms，仅说明端口可达，不代表代理认证成功。");showPage("节点");});}
    private void testNodes(String id){work("内核代理验证",()->id==null?CoreClient.call(this,"POST","/api/nodes/speedtest",new JSONObject()):CoreClient.call(this,"POST","/api/nodes/"+id+"/speedtest",new JSONObject()),v->{dialog("测速结果",pretty(v));if(page.equals("节点"))showPage("节点");});}
    private void policies(){
        heading("路由、DNS 与配置");hint("保存配置后重新连接 VPN 生效。自定义策略组、链式代理与端口映射可通过完整参数或 YAML 覆写配置。");
        work("读取配置",()->CoreClient.object(this,"GET","/api/proxy/config",null),v->{if(!page.equals("策略"))return;JSONObject config=(JSONObject)v;
            String[] modes={"全局代理","全部直连","自定义规则","大陆直连","GFW 规则","订阅规则","链式代理"};String[] ids={"global","direct","rule","china_direct","gfw_list","subscription","chain"};
            Spinner mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes));mode.setSelection(Math.max(0,Arrays.asList(ids).indexOf(config.optString("routing_mode"))));content.addView(mode);
            JSONObject dns=config.optJSONObject("dns");EditText nameservers=field(content,"DNS（IP 或 HTTPS，每行一个）",dns==null?"":join(dns.optJSONArray("nameservers")),2);
            CheckBox auto=check(content,"自动选择 / 故障转移策略组",config.optBoolean("url_test"));
            Spinner strategy=new Spinner(this);String[] strategies={"url_test","fallback","load_balance"};strategy.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"最低延迟","故障转移","负载均衡"}));strategy.setSelection(Math.max(0,Arrays.asList(strategies).indexOf(config.optString("auto_strategy"))));content.addView(strategy);
            EditText yaml=field(content,"YAML 配置覆写",config.isNull("override_yaml")?"":config.optString("override_yaml"),3);
            EditText script=field(content,"Rhai 脚本覆写",config.isNull("override_script")?"":config.optString("override_script"),2);
            action("保存路由与 DNS",()->work("保存配置",()->{JSONObject next=new JSONObject(config.toString());next.put("routing_mode",ids[mode.getSelectedItemPosition()]);next.put("url_test",auto.isChecked()).put("auto_strategy",strategies[strategy.getSelectedItemPosition()]);next.put("override_yaml",yaml.getText().toString().trim().isEmpty()?JSONObject.NULL:yaml.getText().toString());next.put("override_script",script.getText().toString().trim().isEmpty()?JSONObject.NULL:script.getText().toString());JSONArray ns=new JSONArray();for(String line:nameservers.getText().toString().split("\n"))if(!line.trim().isEmpty())ns.put(line.trim());next.getJSONObject("dns").put("nameservers",ns);return CoreClient.call(this,"PUT","/api/proxy/config",next);},value->toast("配置已保存，重新连接后生效")));
            actions("导入 / 编辑分流规则",this::editRules,"完整参数 JSON",()->editJson("完整代理参数",config,value->work("保存完整参数",()->CoreClient.call(this,"PUT","/api/proxy/config",value),x->toast("已保存"))));
            actions("更新 GeoIP / GeoSite",()->work("下载 Geo 规则库",()->CoreClient.call(this,"POST","/android/geo",new JSONObject()),x->dialog("Geo 数据",pretty(x))),"导出当前配置",()->{if(selectedId.isEmpty()){dialog("选择节点","请先选择节点");return;}work("编译当前配置",()->CoreClient.config(this,selectedId),x->saveDocument("vproxy-mihomo.json",pretty(x)));});
        });
    }
    private void editRules(){work("读取规则",()->CoreClient.call(this,"GET","/android/rules",null),v->{LinearLayout l=form();EditText rules=field(l,"每行一条 Mihomo 规则",((JSONObject)v).optString("text"),7);new AlertDialog.Builder(this).setTitle("分流规则").setView(l).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->work("解析规则",()->CoreClient.call(this,"POST","/android/rules",new JSONObject().put("text",rules.getText().toString())),x->dialog("规则解析结果",pretty(x)))).show();});}
    private void monitor(){
        heading("运行监控");actions("活跃连接",this::connections,"策略组 / 热切换",this::groups);
        actions("运行规则",()->controllerView("运行规则","/rules"),"内核状态",()->controllerView("内核状态","/version"));
        actions("DNS 查询",this::dnsQuery,"运行日志",this::showLogs);
        actions("任务进度",()->work("读取任务",()->CoreClient.call(this,"GET","/api/tasks",null),v->dialog("后台任务",pretty(v))),"内核管理",()->work("检测 APK 内核",()->CoreClient.call(this,"GET","/api/kernels",null),v->dialog("内核状态",pretty(v))));
        hint("流量和连接来自运行中的 Mihomo 控制接口。Android 普通应用权限下无法完整读取其他应用的进程信息。当前 VPN 引擎为 Mihomo，其他五种编译器用于配置导出。");
        actions("实际代理健康",this::healthCheck,"订阅和健康历史",()->work("读取历史",()->CoreClient.call(this,"GET","/android/history",null),v->dialog("历史记录",pretty(v))));
        actions("出口 IP 画像",()->confirm("经当前代理获取出口 IP，并向 IP 查询服务请求归属与风险信息？",()->work("查询出口 IP",()->{if(!ProxyVpnService.running)throw new Exception("请先连接 VPN");return CoreClient.call(this,"POST","/android/ip-risk",new JSONObject().put("proxy_url","http://127.0.0.1:"+ProxyVpnService.mixedPort));},v->dialog("出口画像",pretty(v)))),"流量按域名汇总",this::domainTraffic);
    }
    private void controllerView(String name,String path){work(name,()->ControllerClient.request("GET",path,null),v->dialog(name,pretty(v)));}
    private void connections(){work("读取活跃连接",()->ControllerClient.request("GET","/connections",null),v->{JSONArray list=((JSONObject)v).optJSONArray("connections");if(list==null||list.length()==0){dialog("活跃连接","当前没有连接");return;}String[] labels=new String[list.length()];for(int i=0;i<list.length();i++){JSONObject c=list.optJSONObject(i),m=c.optJSONObject("metadata");labels[i]=(m==null?"":m.optString("host",m.optString("destinationIP"))+":"+m.optString("destinationPort"))+" · "+bytes(c.optLong("download"));}new AlertDialog.Builder(this).setTitle("连接详情 / 关闭").setItems(labels,(d,n)->{JSONObject c=list.optJSONObject(n);new AlertDialog.Builder(this).setMessage(pretty(c)).setNegativeButton("关闭",null).setPositiveButton("终止连接",(dd,w)->work("终止连接",()->ControllerClient.request("DELETE","/connections/"+CoreClient.encode(c.optString("id")),null),x->toast("已终止"))).show();}).setNeutralButton("关闭所有连接",(d,w)->confirm("关闭全部代理连接？",()->work("关闭连接",()->ControllerClient.request("DELETE","/connections",null),x->toast("已关闭")))).setPositiveButton("返回",null).show();});}
    private void groups(){work("读取策略组",()->ControllerClient.request("GET","/proxies",null),v->{JSONObject proxies=((JSONObject)v).optJSONObject("proxies");ArrayList<String> names=new ArrayList<>();Iterator<String> keys=proxies.keys();while(keys.hasNext()){String k=keys.next();if(proxies.optJSONObject(k).has("all"))names.add(k);}new AlertDialog.Builder(this).setTitle("策略组").setItems(names.toArray(new String[0]),(d,n)->{String group=names.get(n);JSONObject g=proxies.optJSONObject(group);JSONArray all=g.optJSONArray("all");String[] items=new String[all.length()];for(int i=0;i<items.length;i++)items[i]=all.optString(i);new AlertDialog.Builder(this).setTitle(group+" · 当前 "+g.optString("now")).setItems(items,(dd,k)->work("切换策略",()->ControllerClient.request("PUT","/proxies/"+CoreClient.encode(group),new JSONObject().put("name",items[k])),x->toast("已切换至 "+items[k]))).show();}).setPositiveButton("返回",null).show();});}
    private void dnsQuery(){LinearLayout l=form();EditText name=field(l,"域名","example.com",1);new AlertDialog.Builder(this).setTitle("通过内核查询 DNS").setView(l).setNegativeButton("取消",null).setPositiveButton("查询",(d,w)->controllerView("DNS 结果","/dns/query?name="+CoreClient.encode(name.getText().toString())+"&type=A")).show();}
    private void healthCheck(){work("实际代理健康检查",()->{
        if(!ProxyVpnService.running)throw new Exception("请先连接 VPN");
        long started=SystemClock.elapsedRealtime();boolean ok=false;String error="";
        HttpURLConnection connection=(HttpURLConnection)new URL("https://www.gstatic.com/generate_204").openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",ProxyVpnService.mixedPort)));
        connection.setConnectTimeout(5000);connection.setReadTimeout(10000);
        try{int code=connection.getResponseCode();ok=code==204;if(!ok)error="HTTP "+code;}catch(Exception e){error=e.getMessage();}finally{connection.disconnect();}
        JSONObject result=new JSONObject().put("ok",ok).put("latency_ms",SystemClock.elapsedRealtime()-started).put("error",error).put("node_id",ProxyVpnService.activeNodeId).put("node_name",ProxyVpnService.activeNodeName);
        CoreClient.call(this,"POST","/android/health",result);return result;
    },v->dialog("实际代理检查",pretty(v)));}
    private void settings(){
        heading("后台与设置");work("读取设置",()->CoreClient.object(this,"GET","/api/settings",null),v->{if(!page.equals("设置"))return;JSONObject s=(JSONObject)v;
            CheckBox auto=check(content,"后台定时更新订阅（Android 调度，最短 15 分钟）",s.optBoolean("auto_fetch_enabled"));EditText interval=field(content,"更新间隔（分钟）",s.optString("auto_fetch_interval_min","60"),1);
            CheckBox autoTest=check(content,"定时拉取后自动执行内核测速",s.optBoolean("auto_speedtest_enabled"));
            CheckBox download=check(content,"单节点测速包含吞吐测试",s.optBoolean("speedtest_download"));
            CheckBox failover=check(content,"连接期间健康失败时自动切换候选",prefs().getBoolean("android_failover",false));
            CheckBox api=check(content,"VPN 运行期间启用本地管理 API（127.0.0.1:18080）",prefs().getBoolean("android_api",false));
            EditText bypass=field(content,"不经过 VPN 的应用包名（每行一个）",prefs().getString("bypass_apps",""),2);
            action("保存设置",()->work("保存设置",()->{int minutes=Math.max(15,Integer.parseInt(interval.getText().toString().trim()));Object result=CoreClient.call(this,"PUT","/api/settings",new JSONObject().put("auto_fetch_enabled",auto.isChecked()).put("auto_fetch_interval_min",minutes).put("auto_speedtest_enabled",autoTest.isChecked()).put("speedtest_download",download.isChecked()));prefs().edit().putBoolean("android_failover",failover.isChecked()).putBoolean("android_api",api.isChecked()).putString("bypass_apps",bypass.getText().toString().trim()).apply();RefreshJobService.schedule(this,auto.isChecked(),minutes);return result;},x->toast("已保存；VPN 选项重新连接后生效")));
            actions("完整应用设置",()->editJson("完整应用设置",s,value->work("保存应用设置",()->CoreClient.call(this,"PUT","/api/settings",value),x->toast("已保存"))),"API 访问密钥",()->dialog("本地管理 API","地址：http://127.0.0.1:18080\n请求头：Authorization: Bearer "+LocalApiServer.token(this)+"\n仅在启用设置且 VPN 运行期间可用。"));
            actions("导出完整备份",()->work("导出备份",()->CoreClient.call(this,"GET","/android/backup",null),x->saveDocument("vproxy-v2-backup.json",pretty(x))),"恢复备份",()->openDocument(14));
            actions("导出全部节点 URI",()->exportAll("uri"),"导出 Clash 订阅",()->exportAll("clash"));action("关于 / 许可证",this::about);
            actions("发布服务器设置",this::publisherSettings,"推送已验证节点",()->confirm("把已通过真实代理验证的节点和凭据推送到你配置的发布服务器？",()->work("推送节点",()->CoreClient.call(this,"POST","/android/publish",new JSONObject()),x->dialog("发布结果",pretty(x)))));
            action("清理去重并优化名称",()->confirm("对节点池去重并优化显示名称？",()->work("节点池清理",()->CoreClient.call(this,"POST","/android/cleanup",new JSONObject()),x->{dialog("清理结果",pretty(x));})));
            hint("后台任务由 Android 调度，执行时间可能延后。完整设置保留桌面字段；Windows 提权、注册表与托盘选项不适用于 Android。");
        });
    }
    private void editJson(String name,JSONObject value,java.util.function.Consumer<JSONObject> apply){LinearLayout l=form();EditText e=field(l,"JSON 参数",pretty(value),9);ScrollView scroll=new ScrollView(this);scroll.addView(l);new AlertDialog.Builder(this).setTitle(name).setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{try{apply.accept(new JSONObject(e.getText().toString()));}catch(Exception error){dialog("JSON 错误",error.getMessage());}}).show();}
    private void publisherSettings(){work("读取发布设置",()->CoreClient.object(this,"GET","/api/settings",null),v->{JSONObject s=(JSONObject)v;LinearLayout l=form();EditText url=field(l,"服务器 HTTPS 地址",s.optString("publisher_api"),1),token=field(l,"推送 Token",s.optString("publisher_token"),1);token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);new AlertDialog.Builder(this).setTitle("发布服务器").setView(l).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->work("保存发布设置",()->CoreClient.call(this,"PUT","/api/settings",new JSONObject().put("publisher_api",url.getText().toString().trim()).put("publisher_token",token.getText().toString().trim())),x->toast("已保存；不会自动推送"))).show();});}
    private void domainTraffic(){work("汇总当前连接",()->ControllerClient.request("GET","/connections",null),v->{TreeMap<String,long[]> totals=new TreeMap<>();JSONArray list=((JSONObject)v).optJSONArray("connections");if(list!=null)for(int i=0;i<list.length();i++){JSONObject c=list.optJSONObject(i),m=c.optJSONObject("metadata");String host=m==null?"unknown":m.optString("host");if(host.isEmpty()&&m!=null)host=m.optString("destinationIP");long[] bytes=totals.get(host);if(bytes==null){bytes=new long[3];totals.put(host,bytes);}bytes[0]+=c.optLong("download");bytes[1]+=c.optLong("upload");bytes[2]++;}StringBuilder report=new StringBuilder("仅汇总当前活跃连接，关闭后的历史由内核保留能力决定。\n\n");for(Map.Entry<String,long[]> e:totals.entrySet())report.append(e.getKey()).append("  ↓ ").append(bytes(e.getValue()[0])).append("  ↑ ").append(bytes(e.getValue()[1])).append(" · ").append(e.getValue()[2]).append(" 条\n");dialog("按域名流量",report.toString());});}
    private void exportAll(String kind){work("导出节点",()->CoreClient.call(this,"GET","/api/export/"+kind+"?quality_only=false",null),v->saveDocument("vproxy-nodes."+(kind.equals("clash")?"yaml":"txt"),v instanceof JSONObject?((JSONObject)v).optString("text",pretty(v)):pretty(v)));}
    private void about(){try{dialog("VProxy Android v2 · 2.0.0-beta.1","基于上游 VProxy 1.16.7\nRust 数据管理 + Android VpnService + Mihomo\n\n其他五种内核目前提供配置导出，尚未接入 VPN 运行。\n\n"+CoreClient.read(getAssets().open("THIRD_PARTY_NOTICES.txt"),100000));}catch(Exception e){dialog("关于",e.getMessage());}}
    private void showLogs(){work("读取日志",()->{File log=new File(getFilesDir(),"core/mihomo.log");if(!log.isFile())return "暂无日志";try(RandomAccessFile f=new RandomAccessFile(log,"r")){f.seek(Math.max(0,f.length()-30000));byte[] data=new byte[(int)(f.length()-f.getFilePointer())];f.readFully(data);return new String(data,StandardCharsets.UTF_8);}},v->dialog("内核运行日志",(String)v));}
    private void toggleVpn(){if(ProxyVpnService.running){startService(new Intent(this,ProxyVpnService.class).setAction("stop"));return;}if(selectedId.isEmpty()){dialog("选择节点","请先在节点页选择一个节点");return;}work("编译连接配置",()->CoreClient.config(this,selectedId),v->{pendingConfig=((JSONObject)v).toString();Intent consent=VpnService.prepare(this);if(consent==null)startVpn();else startActivityForResult(consent,11);});}
    private void startVpn(){startForegroundService(new Intent(this,ProxyVpnService.class).putExtra("config",pendingConfig).putExtra("node_id",selectedId).putExtra("node_name",selectedName));pendingConfig=null;}
    private void openDocument(int request){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),request);}
    private void saveDocument(String name,String value){exportText=value;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name),13);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==11){if(result==RESULT_OK)startVpn();else pendingConfig=null;return;}if(result!=RESULT_OK||data==null)return;
        if(request==12||request==14)work("读取文件",()->CoreClient.read(getContentResolver().openInputStream(data.getData()),8*1024*1024),v->{if(request==12)importText((String)v);else confirm("从备份合并节点和订阅，并恢复设置？",()->work("恢复备份",()->{Object restored=CoreClient.call(this,"POST","/android/restore",new JSONObject((String)v));JSONObject settings=CoreClient.object(this,"GET","/api/settings",null);RefreshJobService.schedule(this,settings.optBoolean("auto_fetch_enabled"),settings.optInt("auto_fetch_interval_min",60));return restored;},x->{selectedId=prefs().getString("selectedId","");selectedName=prefs().getString("selectedName","");dialog("恢复结果",pretty(x));showPage(page);}));});
        else if(request==13&&exportText!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(exportText.getBytes(StandardCharsets.UTF_8));toast("已导出，文件可能包含节点凭据，请妥善保管");}catch(Exception e){dialog("导出失败",e.getMessage());}finally{exportText=null;}}
    }
    private static String pretty(Object v){try{if(v instanceof JSONObject)return ((JSONObject)v).toString(2);if(v instanceof JSONArray)return ((JSONArray)v).toString(2);}catch(Exception ignored){}return String.valueOf(v);}
    private static String join(JSONArray v){if(v==null)return "";StringBuilder s=new StringBuilder();for(int i=0;i<v.length();i++){if(i>0)s.append('\n');s.append(v.optString(i));}return s.toString();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}
}
