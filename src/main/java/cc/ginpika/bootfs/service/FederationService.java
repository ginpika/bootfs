package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.kernel.cluster.*;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import cc.ginpika.bootfs.domain.result.SearchResult;
import com.alibaba.fastjson2.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Remote query results are transient responses, never local authoritative metadata or index sources. */
@Service
public class FederationService {
    private final MetadataStore metadata;
    private final MeiliSearchService search;
    private final Context context;
    private final ExecutorService queries = new ThreadPoolExecutor(0,8,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r -> { Thread t=new Thread(r,"bootfs-federation-query"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
    public FederationService(MetadataStore metadata,MeiliSearchService search,Context context) { this.metadata=metadata; this.search=search; this.context=context; }
    public Map<String,String> peers() { return metadata instanceof DynamicMetadataStore d ? d.peerEndpoints() : Map.of(); }
    public String tokenFor(String url) throws IOException {
        if (!(metadata instanceof DynamicMetadataStore d)) throw new IOException("Peer transport is unavailable in legacy mode"); return d.tokenFor(url);
    }
    public ClusterCredentials identity() { return ((DynamicMetadataStore)metadata).transportIdentity(); }
    public JSONObject call(String url,String path,JSONObject body) throws IOException { return ClusterHttp.call(url,path,body,tokenFor(url),2000,identity()); }
    public JSONObject localResource(String id) {
        if (context.query(id)==null || metadata.get("/tombstones/"+id)!=null) return new JSONObject(Map.of("found",false));
        JSONObject result=new JSONObject(Map.of("found",true,"nodeId",context.uuid));
        result.put("document",search.findDocument("full-text",id)); result.put("metadata",metadata.get("/files/"+id+"/meta"));
        return result;
    }
    public JSONObject locate(String id) {
        if (id==null || !id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid resource identity");
        Map<String,JSONObject> replies=fanout((node,url) -> call(url,"data/resource/"+id,null));
        List<JSONObject> found=new ArrayList<>();
        replies.forEach((node,result) -> { if (result.getBooleanValue("found")) { result.put("url",peers().get(node)); found.add(result); } });
        if (found.size()>1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Resource UUID exists on multiple nodes; use the owning node URL");
        if (found.isEmpty() && replies.size()<peers().size()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Some resource owners are unavailable; absence cannot be confirmed");
        return found.isEmpty() ? null : found.get(0);
    }
    @FunctionalInterface private interface Query { JSONObject run(String node,String url) throws IOException; }
    private Map<String,JSONObject> fanout(Query query) {
        Map<String,Future<JSONObject>> jobs=new TreeMap<>();
        peers().forEach((node,url) -> { try { jobs.put(node,queries.submit(() -> query.run(node,url))); } catch(RejectedExecutionException saturated) { jobs.put(node,CompletableFuture.failedFuture(saturated)); } });
        Map<String,JSONObject> results=new TreeMap<>(); long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2500);
        jobs.forEach((node,future) -> {
            try { results.put(node,future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)); }
            catch(Exception unavailable) { future.cancel(true); }
        }); return results;
    }
    public JSONObject localSearch(JSONObject request) {
        String query=request.getString("query");
        List<String> tags=request.getJSONArray("tags")==null ? List.of() : request.getJSONArray("tags").toJavaList(String.class);
        int offset=request.getIntValue("offset"),limit=request.getIntValue("limit");
        if (offset<0 || limit<0 || limit>1000) throw new IllegalArgumentException("Invalid pagination");
        SearchResult result=search.searchResources(query,tags,offset,limit,request.getBooleanValue("includeAll"));
        return new JSONObject(Map.of("hits",result.getHits(),"total",result.getTotalHits(),"nodeId",context.uuid));
    }
    public JSONObject search(String query,List<String> tags,int offset,int limit,boolean includeAll) {
        JSONObject countRequest=new JSONObject(Map.of("query",Objects.toString(query,""),"tags",tags,"offset",0,"limit",0,"includeAll",includeAll));
        Map<String,JSONObject> pages=fanout((node,url) -> call(url,"data/search",countRequest));
        pages.put(context.uuid,localSearch(countRequest));
        Map<String,String> endpoints=peers();
        Set<String> missing=new TreeSet<>(endpoints.keySet()); missing.removeAll(pages.keySet());
        long total=pages.values().stream().mapToLong(p -> p.getLongValue("total")).sum();
        JSONArray hits=new JSONArray(); long skip=offset;
        for (String node:new TreeSet<>(pages.keySet())) {
            int count=pages.get(node).getIntValue("total");
            if (skip>=count) { skip-=count; continue; }
            if (hits.size()>=limit) break;
            JSONObject request=new JSONObject(Map.of("query",Objects.toString(query,""),"tags",tags,"offset",(int)skip,"limit",limit-hits.size(),"includeAll",includeAll)); skip=0;
            try {
                JSONObject page=node.equals(context.uuid) ? localSearch(request) : call(endpoints.get(node),"data/search",request);
                for (Object raw:page.getJSONArray("hits")) {
                    JSONObject hit=JSONObject.from(raw); hit.put("originNodeId",node);
                    if (!node.equals(context.uuid)) hit.put("originEndpoint",endpoints.get(node));
                    hits.add(hit);
                }
            } catch(IOException unavailable) { missing.add(node); }
        }
        return new JSONObject(Map.of("hits",hits,"total",Math.min(Integer.MAX_VALUE,total),"partial",!missing.isEmpty(),"unavailableNodes",missing));
    }
    @javax.annotation.PreDestroy public void close() { queries.shutdownNow(); }
}
