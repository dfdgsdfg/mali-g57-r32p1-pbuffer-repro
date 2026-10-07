package dev.repro.nativegles;

import java.io.BufferedReader;
import java.io.Reader;

/** Parse the public MTK Mali page table, retaining only this process's counts. */
final class MaliMemoryUsage {
    boolean enabled;
    int contexts;
    long pages;
    static MaliMemoryUsage read(Reader reader,int pid)throws Exception {
        MaliMemoryUsage usage=new MaliMemoryUsage();boolean recognized=false;
        BufferedReader input=reader instanceof BufferedReader?(BufferedReader)reader:new BufferedReader(reader);
        String line;
        while((line=input.readLine())!=null){
            if(line.contains("doesn't be enabled"))return usage;
            String[] parts=line.trim().split("\\s+");
            if(parts.length>=2&&parts[0].startsWith("mali"))recognized=true;
            if(parts.length==3&&parts[0].startsWith("kctx-")){
                recognized=true;long pages=Long.parseLong(parts[1]);int owner=Integer.parseInt(parts[2]);
                if(pages<0)throw new IllegalStateException("negative GPU page count");
                if(owner==pid){usage.contexts++;usage.pages+=pages;}
            }
        }
        if(!recognized)throw new IllegalStateException("unrecognized GPU page table");
        usage.enabled=true;return usage;
    }
}
