package dev.repro.nativegles;

import android.os.Debug;
import android.util.Log;
import android.system.Os;
import android.system.OsConstants;
import android.os.SystemClock;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Map;
import java.io.BufferedReader;
import java.io.FileReader;

/** Process-level telemetry; it cannot account for every vendor GPU allocation. */
final class MemorySampler {
    private final long started;
    private final boolean mappingEnabled,gpuAccountingEnabled;
    private JSONObject mapping;
    private long mappingCount,nextMapping;
    private volatile boolean running=true;
    private volatile JSONObject latest;
    private Thread thread;
    MemorySampler(long started){this(started,false);}
    MemorySampler(long started,boolean mappingEnabled){this(started,mappingEnabled,false);}
    MemorySampler(long started,boolean mappingEnabled,boolean gpuAccountingEnabled){this.started=started;this.mappingEnabled=mappingEnabled;this.gpuAccountingEnabled=gpuAccountingEnabled;}
    void start(){thread=new Thread(this::sampleLoop,"memory-sampler");thread.start();}
    void stop(){running=false;if(thread!=null)thread.interrupt();}
    private void sampleLoop(){
        long count=0,peakRss=0,peakPss=0,peakNative=0;
        while(running){
            long begin=SystemClock.elapsedRealtime();JSONObject j=new JSONObject();
            try{
                long rss=-1,hwm=-1,swap=-1;
                try(BufferedReader input=new BufferedReader(new FileReader("/proc/self/status"))){
                    String line;while((line=input.readLine())!=null){
                        if(line.startsWith("VmRSS:"))rss=statusKb(line);
                        else if(line.startsWith("VmHWM:"))hwm=statusKb(line);
                        else if(line.startsWith("VmSwap:"))swap=statusKb(line);
                    }
                }
                if(gpuAccountingEnabled){
                    JSONObject status=readProcessStatus();j.put("rss_anon_kb",status.getLong("rss_anon_kb")).put("rss_file_kb",status.getLong("rss_file_kb")).put("rss_shmem_kb",status.getLong("rss_shmem_kb"));
                    j.put("gpu_accounting",readGpuAccounting());
                }
                if(rss<=0||hwm<=0||swap<0)throw new IllegalStateException("missing process memory fields");
                if(mappingEnabled&&begin>=nextMapping){sampleMappings();nextMapping=SystemClock.elapsedRealtime()+5000;}
                Debug.MemoryInfo info=new Debug.MemoryInfo();Debug.getMemoryInfo(info);
                long pss=info.getTotalPss(),nativeHeap=Debug.getNativeHeapAllocatedSize();
                Runtime runtime=Runtime.getRuntime();long javaHeap=runtime.totalMemory()-runtime.freeMemory();
                peakRss=Math.max(peakRss,rss);peakPss=Math.max(peakPss,pss);peakNative=Math.max(peakNative,nativeHeap);
                j.put("rss_kb",rss).put("vm_hwm_kb",hwm).put("swap_kb",swap).put("pss_kb",pss);
                j.put("private_dirty_kb",info.getTotalPrivateDirty()).put("android_graphics_kb",Long.parseLong(info.getMemoryStat("summary.graphics")));
                j.put("native_heap_allocated_bytes",nativeHeap).put("java_heap_used_bytes",javaHeap);
                j.put("peak_rss_kb",peakRss).put("peak_pss_kb",peakPss).put("peak_native_heap_allocated_bytes",peakNative).put("error","");
            }catch(Exception e){try{j.put("error",e.toString());}catch(Exception ignored){}}
            if(mappingEnabled&&mapping!=null){try{j.put("mappings",new JSONObject(mapping.toString()));}catch(Exception e){throw new IllegalStateException(e);}}
            long end=SystemClock.elapsedRealtime();
            try{j.put("source","proc_status_and_android_debug").put("sample_count",++count).put("sample_elapsed_ms",end-started).put("sample_duration_ms",end-begin);}catch(Exception e){throw new IllegalStateException(e);}
            latest=j; // Publish once; never mutate the published sample.
            try{Thread.sleep(Math.max(1,1000-(end-begin)));}catch(InterruptedException e){break;}
        }
    }
    private static JSONObject stats(SmapsSummary.Stats stats)throws Exception {
        JSONObject j=new JSONObject().put("map_count",stats.mapCount);
        for(int i=0;i<SmapsSummary.KEYS.length;i++)j.put(SmapsSummary.KEYS[i],stats.values[i]);return j;
    }
    private void sampleMappings(){
        long begin=SystemClock.elapsedRealtime();JSONObject detail=new JSONObject(),overview=new JSONObject();
        try{
            if(gpuAccountingEnabled)detail.put("status_before",readProcessStatus());
            SmapsSummary summary;
            try(FileReader input=new FileReader("/proc/self/smaps")){summary=SmapsSummary.read(input);}
            if(gpuAccountingEnabled)detail.put("status_after",readProcessStatus());
            ArrayList<Map.Entry<String,SmapsSummary.Stats>> groups=new ArrayList<>(summary.groups.entrySet());
            groups.sort((a,b)->Long.compare(b.getValue().values[1],a.getValue().values[1]));
            JSONArray top=new JSONArray(),brief=new JSONArray();
            for(int i=0;i<Math.min(12,groups.size());i++){
                Map.Entry<String,SmapsSummary.Stats> entry=groups.get(i);String name=entry.getKey();
                if(name.length()>96)name=name.substring(0,96);
                top.put(stats(entry.getValue()).put("name",name));
                if(i<2)brief.put(new JSONObject().put("name",name).put("rss_kb",entry.getValue().values[1]).put("pss_kb",entry.getValue().values[2]));
            }
            detail.put("totals",stats(summary.totals)).put("top_rss",top).put("map_count",summary.totals.mapCount).put("error","");
            overview.put("totals",new JSONObject().put("rss_kb",summary.totals.values[1]).put("pss_kb",summary.totals.values[2])).put("top_rss",brief).put("map_count",summary.totals.mapCount).put("error","");
        }catch(Exception e){try{detail.put("error",e.toString());overview.put("error",e.toString());}catch(Exception ignored){}}
        long end=SystemClock.elapsedRealtime();mappingCount++;
        try{
            for(JSONObject j:new JSONObject[]{detail,overview})j.put("source","proc_self_smaps").put("sample_count",mappingCount).put("sample_elapsed_ms",end-started).put("sample_duration_ms",end-begin);
            mapping=overview;Log.i("NATIVE_MEMORY_MAPS",detail.toString());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static JSONObject readProcessStatus()throws Exception {
        JSONObject j=new JSONObject();String[] fields={"VmRSS:","RssAnon:","RssFile:","RssShmem:","VmHWM:","VmSwap:"};
        String[] keys={"rss_kb","rss_anon_kb","rss_file_kb","rss_shmem_kb","vm_hwm_kb","swap_kb"};
        try(BufferedReader input=new BufferedReader(new FileReader("/proc/self/status"))){
            String line;while((line=input.readLine())!=null)for(int i=0;i<fields.length;i++)if(line.startsWith(fields[i]))j.put(keys[i],statusKb(line));
        }
        for(String key:keys)if(!j.has(key)||j.getLong(key)<0)throw new IllegalStateException("missing process status "+key);
        return j;
    }
    private JSONObject readGpuAccounting()throws Exception {
        JSONObject j=new JSONObject().put("source","proc_mtk_mali_gpu_memory").put("pid",android.os.Process.myPid());
        try{
            MaliMemoryUsage usage;
            try(FileReader input=new FileReader("/proc/mtk_mali/gpu_memory")){usage=MaliMemoryUsage.read(input,android.os.Process.myPid());}
            long pageSize=Os.sysconf(OsConstants._SC_PAGESIZE);
            if(pageSize<=0)throw new IllegalStateException("invalid OS page size");
            j.put("status",usage.enabled?"available":"not_enabled").put("contexts",usage.contexts).put("pages",usage.pages).put("page_size_bytes",pageSize).put("used_kb",usage.pages*pageSize/1024).put("error","");
        }catch(Exception e){j.put("status","unavailable").put("error",e.toString());}
        j.put("sample_elapsed_ms",SystemClock.elapsedRealtime()-started);Log.i("NATIVE_GPU_MEMORY",j.toString());return j;
    }
    private static long statusKb(String line){return Long.parseLong(line.trim().split("\\s+")[1]);}
    JSONObject snapshot(){
        try{
            JSONObject sample=latest;
            JSONObject j=sample==null?new JSONObject():new JSONObject(sample.toString());
            if(j.has("mappings")){JSONObject maps=j.getJSONObject("mappings");maps.put("sample_age_ms",Math.max(0,SystemClock.elapsedRealtime()-started-maps.getLong("sample_elapsed_ms")));}
            j.put("sample_age_ms",sample==null?SystemClock.elapsedRealtime()-started:Math.max(0,SystemClock.elapsedRealtime()-started-sample.getLong("sample_elapsed_ms")));return j;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
