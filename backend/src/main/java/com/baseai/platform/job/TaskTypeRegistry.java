package com.baseai.platform.job;

import org.springframework.stereotype.Component;
import java.util.*;import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskTypeRegistry {
    private final Map<String,Metadata> types=new ConcurrentHashMap<>();
    /** 注册运行时发现的任务类型元数据。 */ public void register(String code,String triggerEntry){types.putIfAbsent(code,new Metadata(code,code,triggerEntry,true));}
    /** 查询已注册任务类型。 */ public List<Metadata> all(){return types.values().stream().sorted(Comparator.comparing(Metadata::code)).toList();}
    public record Metadata(String code,String name,String triggerEntry,boolean cancellable){}
}
