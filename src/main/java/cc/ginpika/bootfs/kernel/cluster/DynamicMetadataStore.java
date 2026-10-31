package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.kernel.metadata.*;
import com.alibaba.fastjson2.*;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Autonomous local authority. Federation membership never enters the resource database. */
public final class DynamicMetadataStore implements MetadataStore {
    private final RocksMetadataStore local;
    private final Path manifestFile;
    private final String nodeId, endpoint;
    private final ClusterCredentials identity;
    private final Map<String,Long> nonces=new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private JSONObject manifest;

    public DynamicMetadataStore(TfsConfig config, String unusedAddress, String unusedToken, boolean unusedNetworkFlag) throws IOException {
        this(config);
    }
    public DynamicMetadataStore(TfsConfig config, String unusedAddress, java.util.function.Supplier<String> unusedToken, boolean unusedNetworkFlag) throws IOException {
        this(config);
    }
    public DynamicMetadataStore(TfsConfig config) throws IOException {
        identity=new ClusterCredentials(config,"");
        Path kernel = Path.of(config.getRootPath(), "kernel");
        if (Files.exists(kernel.resolve("raft-identity.json")) || Files.exists(kernel.resolve("dynamic/node.json"))) {
            throw new IOException("Legacy shared-Raft storage requires portable backup/restore into a new local node; original storage has not been changed");
        }
        nodeId = new NodeIdentity(config).id(); endpoint = normalizeEndpoint(config.getWebEntrypoint());
        manifestFile = kernel.resolve("cluster/node.json");
        if (Files.exists(manifestFile)) {
            manifest = JSON.parseObject(Files.readString(manifestFile));
            if (manifest.getIntValue("formatVersion") != 2 || !nodeId.equals(manifest.getString("nodeId"))
                    || !endpoint.equals(manifest.getString("endpoint"))) throw new IOException("Federation node identity/endpoint differs from stored identity");
            if (!identity.publicKey().equals(manifest.getJSONObject("members").getJSONObject(nodeId).getString("publicKey"))) throw new IOException("Node signing identity differs from its persisted membership identity");
        } else {
            Path directory = manifestFile.getParent();
            if (Files.exists(directory)) try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) throw new IOException("Federation identity is missing while previous state remains; restore the node state");
            }
            manifest = new JSONObject(); manifest.put("formatVersion", 2); manifest.put("nodeId", nodeId); manifest.put("endpoint", endpoint);
            manifest.put("pending", new JSONObject()); manifest.put("outbox", new JSONArray());
            resetMembership(); persist();
        }
        local = new RocksMetadataStore(kernel.resolve("metadata"));
        // Never reinterpret a replicated state-machine copy as an autonomous local authority.
        if (local.appliedIndex() >= 0) { local.close(); throw new IOException("Replicated metadata requires portable backup/restore before opening as local authority"); }
        Map<String,String> versions=new TreeMap<>();
        for (String key:local.scan("/documents/full-text/").keySet()) {
            String id=key.substring("/documents/full-text/".length());
            if (local.get("/resource-revisions/"+id)==null) versions.put("/resource-revisions/"+id,Long.toString(local.revision()));
        }
        if (!versions.isEmpty()) local.writeBatch(versions,Set.of());
    }
    public static String normalizeEndpoint(String input) throws IOException {
        try {
            var uri = java.net.URI.create(input == null ? "" : input.trim());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) throw new IllegalArgumentException();
            return uri.toString().replaceAll("/+$", "");
        } catch (IllegalArgumentException invalid) { throw new IOException("请输入有效的 http:// 或 https:// BootFS 地址，不含账号、查询参数或片段"); }
    }
    private JSONObject object(String key) { return manifest.getJSONObject(key); }
    private JSONObject members() { return object("members"); }
    private JSONObject links() { return object("links"); }
    private static JSONObject copy(JSONObject value) { return JSON.parseObject(value.toJSONString()); }
    private String secret() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private JSONObject descriptor(String url, String incarnation) { return descriptor(url,incarnation,identity.publicKey()); }
    private JSONObject descriptor(String url,String incarnation,String publicKey) { return new JSONObject(Map.of("url",url,"incarnation",incarnation,"publicKey",publicKey)); }
    private JSONObject link(String inbound, String outbound) { return new JSONObject(Map.of("inbound", inbound, "outbound", outbound)); }
    private void resetMembership() {
        if (object("revoked")==null) manifest.put("revoked",new JSONObject());
        if (members()!=null) for (String peer:new ArrayList<>(members().keySet())) if (!nodeId.equals(peer)) retire(peer);
        manifest.put("groupId", UUID.randomUUID().toString()); manifest.put("coordinatorId", nodeId); manifest.put("version", 0L);
        manifest.put("members", new JSONObject(Map.of(nodeId, descriptor(endpoint, UUID.randomUUID().toString()))));
        manifest.put("links", new JSONObject()); manifest.put("pending", new JSONObject());
        manifest.put("departed", new JSONObject()); manifest.put("admissions", new JSONObject()); manifest.remove("lastError");
    }
    private void persist() throws IOException {
        Files.createDirectories(manifestFile.getParent());
        Path stage = Files.createTempFile(manifestFile.getParent(), ".node-", ".tmp");
        try {
            Files.setPosixFilePermissions(stage, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.writeString(stage, manifest.toJSONString(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(stage, manifestFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            DurableFiles.syncDirectory(manifestFile.getParent());
        } finally { Files.deleteIfExists(stage); }
    }
    public String nodeId() { return nodeId; }
    public String endpoint() { return endpoint; }
    public ClusterCredentials transportIdentity() { return identity; }
    private JSONObject call(String url,String path,JSONObject body,String token,int timeout) throws IOException { return ClusterHttp.call(url,path,body,token,timeout,identity); }
    public boolean available() { return true; }
    public synchronized JSONObject status() {
        JSONObject peers = new JSONObject(); members().forEach((id, raw) -> peers.put(id, ((JSONObject)raw).getString("url")));
        JSONObject status = new JSONObject(Map.of("nodeId", nodeId, "endpoint", endpoint, "groupId", manifest.getString("groupId"),
                "phase", "ACTIVE", "metadataMode", "local", "independent", members().size() == 1,
                "coordinatorId", manifest.getString("coordinatorId"), "version", manifest.getLongValue("version"), "peers", peers));
        status.put("lastError", manifest.getString("lastError")); status.put("pendingNotifications", manifest.getJSONArray("outbox").size()); return status;
    }
    public JSONObject ready() { return status(); }
    public synchronized Map<String,String> peerEndpoints() {
        Map<String,String> peers = new TreeMap<>(); members().forEach((id, raw) -> { if (!nodeId.equals(id)) peers.put(id, ((JSONObject)raw).getString("url")); }); return peers;
    }
    public synchronized String actor(String token, boolean allowPending) {
        if (token == null) return null;
        for (String peer : links().keySet()) if (members().containsKey(peer) && same(links().getJSONObject(peer).getString("inbound"), token)) return peer;
        if (allowPending) for (String peer : object("pending").keySet()) {
            JSONObject entry = object("pending").getJSONObject(peer);
            if (entry.getLongValue("expiresAt") > System.currentTimeMillis() && same(entry.getString("inbound"), token)) return peer;
        }
        return null;
    }
    public synchronized boolean verifyPeerRequest(javax.servlet.http.HttpServletRequest request,String token,boolean pending) {
        String actor=actor(token,pending); if (actor==null) return false;
        JSONObject peer=members().getJSONObject(actor); if (peer==null) peer=object("pending").getJSONObject(actor);
        return verifySignature(request,token,peer.getString("publicKey"),true);
    }
    private void retire(String peer) {
        JSONObject grant=links()==null ? null : links().getJSONObject(peer), descriptor=members().getJSONObject(peer);
        if (grant!=null && descriptor!=null) object("revoked").put(ClusterHttp.digest(grant.getString("inbound").getBytes(java.nio.charset.StandardCharsets.UTF_8)),descriptor.getString("publicKey"));
    }
    public synchronized boolean verifyRevokedRequest(javax.servlet.http.HttpServletRequest request,String token) {
        if (token==null || object("revoked")==null) return false;
        String key=object("revoked").getString(ClusterHttp.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return key!=null && verifySignature(request,token,key,false);
    }
    private boolean verifySignature(javax.servlet.http.HttpServletRequest request,String token,String publicKey,boolean consume) {
        String time=request.getHeader("X-BootFS-Time"),nonce=request.getHeader("X-BootFS-Nonce"),digest=request.getHeader("X-BootFS-Digest");
        long now=System.currentTimeMillis();
        try { if (Math.abs(now-Long.parseLong(time))>60_000 || nonce==null || !nonce.matches("[A-Za-z0-9-]{36}") || digest==null || !digest.matches("[a-f0-9]{64}")) return false; }
        catch(NumberFormatException invalid) { return false; }
        if (request.getContentType()!=null && request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json") && !Boolean.TRUE.equals(request.getAttribute("bootfs.peerBodyVerified"))) return false;
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) && !ClusterHttp.digest(new byte[0]).equals(digest)) return false;
        String target=request.getRequestURI()+(request.getQueryString()==null ? "" : "?"+request.getQueryString());
        if (!ClusterCredentials.verify(publicKey,ClusterHttp.signingInput(request.getMethod(),target,token,time,nonce,digest),request.getHeader("X-BootFS-Signature"))) return false;
        if (!consume) return true;
        nonces.entrySet().removeIf(entry -> entry.getValue()<now);
        if (nonces.size()>=100000 || nonces.putIfAbsent(nonce,now+120000)!=null) return false;
        return true;
    }
    private static boolean same(String left, String right) {
        return left != null && right != null && java.security.MessageDigest.isEqual(left.getBytes(java.nio.charset.StandardCharsets.UTF_8), right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public synchronized String tokenFor(String url) throws IOException {
        url = normalizeEndpoint(url);
        for (String peer : members().keySet()) if (!peer.equals(nodeId) && url.equals(members().getJSONObject(peer).getString("url")) && links().containsKey(peer)) return links().getJSONObject(peer).getString("outbound");
        for (String peer : object("pending").keySet()) {
            JSONObject grant = object("pending").getJSONObject(peer);
            if (url.equals(grant.getString("url")) && grant.getLongValue("expiresAt") > System.currentTimeMillis()) return grant.getString("outbound");
        }
        throw new IOException("尚未配对，或该节点已经离开当前集群");
    }
    public synchronized String endpointFor(String actor) {
        JSONObject peer=members().getJSONObject(actor); if (peer==null) peer=object("pending").getJSONObject(actor);
        return peer==null ? null : peer.getString("url");
    }
    public synchronized void paired(String peer, String url, String inbound, String outbound,String publicKey) throws IOException {
        if (peer == null || !peer.matches("[A-Za-z0-9_-]+") || nodeId.equals(peer)) throw new IOException("请输入其他 BootFS 节点的地址");
        url = normalizeEndpoint(url);
        if (members().containsKey(peer) && !url.equals(members().getJSONObject(peer).getString("url"))) throw new IOException("Node identity is already registered at another endpoint");
        if (inbound == null || outbound == null || inbound.length() < 32 || outbound.length() < 32 || publicKey==null) throw new IOException("Invalid peer authorization");
        object("pending").put(peer, new JSONObject(Map.of("url", url, "inbound", inbound, "outbound", outbound,"publicKey",publicKey, "expiresAt", System.currentTimeMillis()+300_000)));
        persist();
    }
    public synchronized void connect(String target) throws IOException {
        target = normalizeEndpoint(target);
        JSONObject remote = call(target, "ready", null, tokenFor(target), 5000);
        if (manifest.getString("groupId").equals(remote.getString("groupId"))) return;
        if (members().size() != 1) throw new IOException("请先退出当前集群，再加入另一个集群");
        JSONObject self = members().getJSONObject(nodeId);
        JSONObject request = new JSONObject(Map.of("nodeId", nodeId, "url", endpoint, "incarnation", self.getString("incarnation"),"publicKey",identity.publicKey()));
        JSONObject joined = call(target, "join", request, tokenFor(target), 10000);
        if (!joined.getJSONObject("members").containsKey(nodeId)) throw new IOException("Join response omitted this node");
        manifest.put("groupId", joined.getString("groupId")); manifest.put("coordinatorId", joined.getString("coordinatorId"));
        manifest.put("version", joined.getLongValue("version")); manifest.put("members", joined.getJSONObject("members"));
        manifest.put("links", joined.getJSONObject("links")); manifest.put("pending", new JSONObject());
        manifest.put("departed", new JSONObject()); manifest.put("admissions", new JSONObject()); persist();
    }
    public synchronized JSONObject join(JSONObject request, String caller) throws IOException {
        String joining = request.getString("nodeId"), url = normalizeEndpoint(request.getString("url"));
        String incarnation = request.getString("incarnation");
        if (joining == null || !joining.matches("[A-Za-z0-9_-]+") || incarnation == null || !incarnation.matches("[A-Za-z0-9_-]+") || nodeId.equals(joining)) throw new IOException("Invalid joining identity");
        if (!Objects.equals(caller, joining) && !members().containsKey(caller)) throw new IOException("Pairing authorization required");
        if (nodeId.equals(manifest.getString("coordinatorId")) && members().containsKey(joining)) {
            JSONObject saved=object("admissions").getJSONObject(joining),member=members().getJSONObject(joining);
            if (saved!=null && url.equals(member.getString("url")) && incarnation.equals(member.getString("incarnation")) && Objects.equals(request.getString("publicKey"),member.getString("publicKey"))) return copy(saved);
            throw new IOException("Node identity is already registered; detach the old instance first");
        }
        if (Objects.equals(caller, joining)) {
            JSONObject pending = object("pending").getJSONObject(joining);
            if (pending == null || !url.equals(pending.getString("url")) || !Objects.equals(pending.getString("publicKey"),request.getString("publicKey"))) throw new IOException("Joining endpoint/key differs from the paired identity");
            request = copy(request); request.put("bootstrap", new JSONObject(Map.of("nodeId", nodeId, "inbound", pending.getString("inbound"), "outbound", pending.getString("outbound"))));
        } else if (request.getJSONObject("bootstrap") == null || !caller.equals(request.getJSONObject("bootstrap").getString("nodeId"))) throw new IOException("Invalid delegated pairing");
        String coordinator = manifest.getString("coordinatorId");
        if (!nodeId.equals(coordinator)) {
            String destination = members().getJSONObject(coordinator).getString("url");
            return call(destination, "join", request, tokenFor(destination), 10000);
        }
        if (members().containsKey(joining)) {
            JSONObject saved = object("admissions").getJSONObject(joining);
            if (saved != null && url.equals(members().getJSONObject(joining).getString("url")) && incarnation.equals(members().getJSONObject(joining).getString("incarnation"))) return copy(saved);
            throw new IOException("Node identity is already registered; detach the old instance first");
        }
        JSONObject oldMembers = copy(members()); JSONObject next = copy(oldMembers); next.put(joining, descriptor(url, incarnation,request.getString("publicKey")));
        long version = manifest.getLongValue("version")+1;
        JSONObject joiningLinks = new JSONObject(); JSONObject bootstrap = request.getJSONObject("bootstrap");
        for (String peer : oldMembers.keySet()) {
            String incoming = secret(), outgoing = secret();
            if (peer.equals(bootstrap.getString("nodeId"))) { incoming=bootstrap.getString("inbound"); outgoing=bootstrap.getString("outbound"); }
            joiningLinks.put(peer, link(outgoing, incoming));
            JSONObject event = membershipEvent(next, version, coordinator, "ADD", joining); event.put("link", link(incoming, outgoing));
            if (peer.equals(nodeId)) links().put(joining, link(incoming, outgoing));
            else queue(oldMembers.getJSONObject(peer).getString("url"), tokenFor(oldMembers.getJSONObject(peer).getString("url")), "membership", event);
        }
        manifest.put("members", next); manifest.put("version", version); object("pending").remove(joining);
        JSONObject response = new JSONObject(Map.of("groupId", manifest.getString("groupId"), "coordinatorId", coordinator,
                "version", version, "members", next, "links", joiningLinks));
        object("admissions").put(joining, copy(response)); persist(); return response;
    }
    private JSONObject membershipEvent(JSONObject members, long version, String coordinator, String operation, String subject) {
        return new JSONObject(Map.of("groupId", manifest.getString("groupId"), "members", members, "version", version,
                "coordinatorId", coordinator, "operation", operation, "subject", subject));
    }
    private void queue(String url, String token, String path, JSONObject body) {
        manifest.getJSONArray("outbox").add(new JSONObject(Map.of("id", UUID.randomUUID().toString(), "url", url, "token", token, "path", path, "body", copy(body))));
    }
    public synchronized void membership(JSONObject event, String caller) throws IOException {
        if (!Objects.equals(manifest.getString("groupId"), event.getString("groupId")) || !Objects.equals(manifest.getString("coordinatorId"), caller)) throw new IOException("Membership update is not from this group's coordinator");
        if (event.getLongValue("version") <= manifest.getLongValue("version")) return;
        JSONObject next = copy(event.getJSONObject("members"));
        for (String peer : object("departed").keySet()) {
            if (next.containsKey(peer) && Objects.equals(next.getJSONObject(peer).getString("incarnation"), object("departed").getString(peer))) next.remove(peer);
        }
        if (!next.containsKey(nodeId)) { resetMembership(); persist(); return; }
        if (!identity.publicKey().equals(next.getJSONObject(nodeId).getString("publicKey"))) throw new IOException("Membership cannot replace this node's signing identity");
        String subject = event.getString("subject");
        if ("ADD".equals(event.getString("operation")) && next.containsKey(subject)) links().put(subject, event.getJSONObject("link"));
        for (String peer:new ArrayList<>(links().keySet())) if (!next.containsKey(peer)) { retire(peer); links().remove(peer); }
        manifest.put("members", next); manifest.put("version", event.getLongValue("version")); manifest.put("coordinatorId", event.getString("coordinatorId"));
        object("pending").remove(subject); persist();
    }
    public synchronized void remove(String id) throws IOException {
        if (nodeId.equals(id)) { leave(); return; }
        if (!members().containsKey(id)) return;
        String coordinator = manifest.getString("coordinatorId");
        if (!nodeId.equals(coordinator)) {
            String target = members().getJSONObject(coordinator).getString("url");
            call(target, "remove", new JSONObject(Map.of("nodeId", id)), tokenFor(target), 5000); return;
        }
        publishRemoval(id);
    }
    private void publishRemoval(String id) throws IOException {
        JSONObject old = copy(members()), next = copy(members()); next.remove(id);
        if (next.isEmpty()) { resetMembership(); persist(); return; }
        String coordinator = nodeId.equals(id) ? new TreeSet<>(next.keySet()).first() : manifest.getString("coordinatorId");
        long version = manifest.getLongValue("version")+1;
        JSONObject event = membershipEvent(next, version, coordinator, "REMOVE", id);
        for (String peer : old.keySet()) if (!peer.equals(nodeId)) {
            String url = old.getJSONObject(peer).getString("url"); queue(url, tokenFor(url), "membership", event);
        }
        object("departed").put(id, old.getJSONObject(id).getString("incarnation"));
        if (nodeId.equals(id)) resetMembership();
        else { retire(id); manifest.put("members", next); manifest.put("version", version); manifest.put("coordinatorId", coordinator); links().remove(id); object("admissions").remove(id); object("pending").remove(id); }
        persist();
    }
    public synchronized void leave() throws IOException {
        if (members().size() == 1) { resetMembership(); persist(); return; }
        if (nodeId.equals(manifest.getString("coordinatorId"))) { publishRemoval(nodeId); return; }
        JSONObject departure = new JSONObject(Map.of("groupId", manifest.getString("groupId"), "nodeId", nodeId,
                "incarnation", members().getJSONObject(nodeId).getString("incarnation")));
        for (String peer : members().keySet()) if (!peer.equals(nodeId)) {
            String url = members().getJSONObject(peer).getString("url"); queue(url, tokenFor(url), "departure", departure);
        }
        resetMembership(); persist();
    }
    public synchronized void departure(JSONObject event, String caller) throws IOException {
        String id = event.getString("nodeId");
        if (!Objects.equals(caller, id) || !manifest.getString("groupId").equals(event.getString("groupId"))) throw new IOException("Invalid departure identity");
        JSONObject member = members().getJSONObject(id);
        if (member == null || !Objects.equals(member.getString("incarnation"), event.getString("incarnation"))) return;
        if (nodeId.equals(manifest.getString("coordinatorId"))) { publishRemoval(id); return; }
        retire(id); object("departed").put(id, member.getString("incarnation")); members().remove(id); links().remove(id); object("pending").remove(id); object("admissions").remove(id);
        persist();
    }
    /** Best-effort durable control-plane delivery. No resource read/write waits for it. */
    public boolean advance() throws IOException {
        JSONArray jobs;
        synchronized (this) { jobs = JSON.parseArray(manifest.getJSONArray("outbox").toJSONString()); }
        Set<String> blocked = new HashSet<>();
        for (Object raw : jobs) {
            JSONObject job=(JSONObject)raw; String url=job.getString("url"); if (blocked.contains(url)) continue;
            try {
                call(url, job.getString("path"), job.getJSONObject("body"), job.getString("token"), 1000);
                synchronized (this) { manifest.getJSONArray("outbox").removeIf(item -> job.getString("id").equals(((JSONObject)item).getString("id"))); manifest.remove("lastError"); persist(); }
            } catch (ClusterHttp.HttpFailure failure) {
                if (failure.status == 410) {
                    synchronized (this) { manifest.getJSONArray("outbox").removeIf(item -> job.getString("id").equals(((JSONObject)item).getString("id"))); persist(); }
                } else { blocked.add(url); synchronized(this) { manifest.put("lastError", "成员通知待重试："+url); persist(); } }
            } catch (IOException failure) { blocked.add(url); synchronized(this) { manifest.put("lastError", "成员通知待重试："+url); persist(); } }
        }
        return true;
    }
    public void resumeLeave() {} // Local detachment is committed before notifications are sent.
    @Override public String get(String key) { return local.get(key); }
    @Override public NavigableMap<String,String> scan(String prefix) { return local.scan(prefix); }
    @Override public long revision() { return local.revision(); }
    @Override public void put(String key,String value) { writeBatch(Map.of(key,value),Set.of()); }
    @Override public boolean delete(String key) { synchronized(local) { boolean existed=local.get(key)!=null; writeBatch(Map.of(),Set.of(key)); return existed; } }
    @Override public void deletePrefix(String prefix) { synchronized(local) { writeBatch(Map.of(),local.scan(prefix).keySet()); } }
    private void affected(Set<String> ids,String key,String value) {
        String id=null;
        if (key.startsWith("/documents/full-text/")) id=key.substring("/documents/full-text/".length());
        else if (key.startsWith("/nodes/"+nodeId+"/catalog/")) id=key.substring(("/nodes/"+nodeId+"/catalog/").length());
        else if (key.startsWith("/tombstones/")) id=key.substring("/tombstones/".length());
        else if (key.startsWith("/files/") && key.endsWith("/meta") && key.split("/").length==4) id=key.split("/")[2];
        else if (key.startsWith("/s3/") && value!=null) {
            try { id=JSON.parseObject(value).getString("uuid"); } catch(RuntimeException invalid) { /* Preserve generic KV compatibility. */ }
        }
        if (id!=null && id.matches("[A-Za-z0-9_-]+")) ids.add(id);
    }
    private Map<String,String> versioned(Map<String,String> puts,Set<String> deletes) {
        Map<String,String> result=new TreeMap<>(puts); Set<String> ids=new TreeSet<>(),keys=new TreeSet<>(deletes); keys.addAll(puts.keySet());
        for (String key:keys) {
            String old=local.get(key),next=puts.get(key); if (Objects.equals(old,next)) continue;
            affected(ids,key,old); affected(ids,key,next);
        }
        long revision=local.revision()+1;
        for (String id:ids) {
            String key="/resource-revisions/"+id;
            long imported=puts.containsKey(key) ? Long.parseLong(puts.get(key)) : 0;
            result.put(key,Long.toString(Math.max(imported,revision)));
        }
        return result;
    }
    @Override public void writeBatch(Map<String,String> puts,Set<String> deletes) { synchronized(local) { local.writeBatch(versioned(puts,deletes),deletes); } }
    @Override public boolean compareAndSet(String key,String expected,String value) {
        return compareAndWrite(Collections.singletonMap(key,expected),value==null?Map.of():Map.of(key,value),value==null?Set.of(key):Set.of());
    }
    @Override public boolean compareAndWrite(Map<String,String> expected,Map<String,String> puts,Set<String> deletes) {
        synchronized(local) {
            for (var entry:expected.entrySet()) if (!Objects.equals(local.get(entry.getKey()),entry.getValue())) return false;
            local.writeBatch(versioned(puts,deletes),deletes); return true;
        }
    }
    @Override public void close() { local.close(); }
}
