package dev.repro.nativegles;

import java.io.BufferedReader;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Aggregate self smaps by mapping name; addresses are never published. */
final class SmapsSummary {
    static final String[] FIELDS={"Size:","Rss:","Pss:","Private_Dirty:","Anonymous:","Swap:"};
    static final String[] KEYS={"size_kb","rss_kb","pss_kb","private_dirty_kb","anonymous_kb","swap_kb"};
    private static final Pattern HEADER=Pattern.compile("^[0-9a-fA-F]+-[0-9a-fA-F]+\\s+[-rwxps]{4}\\s+.*");
    static final class Stats {int mapCount;final long[] values=new long[FIELDS.length];}
    final Stats totals=new Stats();
    final Map<String,Stats> groups=new LinkedHashMap<>();
    static SmapsSummary read(Reader reader)throws Exception {
        SmapsSummary summary=new SmapsSummary();Stats current=null;
        BufferedReader input=reader instanceof BufferedReader?(BufferedReader)reader:new BufferedReader(reader);
        String line;
        while((line=input.readLine())!=null){
            if(HEADER.matcher(line).matches()){
                String[] parts=line.trim().split("\\s+",6);String name=parts.length==6?parts[5]:"[anonymous]";
                current=summary.groups.get(name);if(current==null){current=new Stats();summary.groups.put(name,current);}
                current.mapCount++;summary.totals.mapCount++;continue;
            }
            if(current==null)continue;
            for(int i=0;i<FIELDS.length;i++)if(line.startsWith(FIELDS[i])){
                long value=Long.parseLong(line.trim().split("\\s+")[1]);
                current.values[i]+=value;summary.totals.values[i]+=value;break;
            }
        }
        if(summary.totals.mapCount==0)throw new IllegalStateException("no self smaps mappings");
        return summary;
    }
}
