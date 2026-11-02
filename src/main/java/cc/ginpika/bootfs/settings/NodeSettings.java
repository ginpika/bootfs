package cc.ginpika.bootfs.settings;

import com.alibaba.fastjson2.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** One validated immutable snapshot, published only after its synchronous DB write succeeds. */
@Component
public final class NodeSettings implements AutoCloseable {
    private final SettingsDatabase database;
    private final Path root;
    private final SettingsValues startup;
    private volatile SettingsValues current;
    private JSONObject state;
    public NodeSettings(Environment env) throws IOException {
        root=Path.of(env.getProperty("tfs.root-path",env.getProperty("TFS_ROOT_PATH","./bootfs")));
        // Context initializers (including tests) can supply a root after environment post-processing.
        // Never import the bootstrap settings of a different node into this node.
        if (env instanceof org.springframework.core.env.ConfigurableEnvironment configurable
                && !root.toAbsolutePath().normalize().toString().equals(env.getProperty("bootfs.settings.loaded-root"))) {
            configurable.getPropertySources().remove("nodeDatabaseSettings");
        }
        database=new SettingsDatabase(root);
        try { state=database.initialize(env,root); current=new SettingsValues(state.getJSONObject("values")); startup=current; }
        catch (Exception failure) { database.close(); throw failure; }
    }
    public SettingsValues current() { return current; }
    public synchronized Map<String,Object> view() {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("revision",state.getLongValue("revision"));
        result.put("values",current.publicView()); result.put("pending",state.containsKey("pending") ? new LinkedHashMap<>(state.getJSONObject("pending")) : Map.of());
        result.put("restartRequired", !startup.string("forwardHeadersStrategy").equals(current.string("forwardHeadersStrategy"))
                || !startup.string("internalProxies").equals(current.string("internalProxies")) || state.containsKey("pending"));
        result.put("activeProxy",Map.of("forwardHeadersStrategy",startup.string("forwardHeadersStrategy"),"internalProxies",startup.string("internalProxies")));
        if (state.containsKey("migrationWarning")) result.put("migrationWarning",state.getString("migrationWarning"));
        if (state.containsKey("transitionError")) result.put("transitionError",state.getString("transitionError"));
        return result;
    }
    public synchronized Map<String,Object> update(long revision, SettingsValues next, boolean addressConfirmed, boolean bucketConfirmed,
                                                   cc.ginpika.bootfs.kernel.metadata.MetadataStore metadata) throws IOException {
        if (revision!=state.getLongValue("revision")) throw new IllegalStateException("设置已被其他页面修改，请重新加载");
        Map<String,Object> requested=new LinkedHashMap<>(next.map()); Map<String,Object> pending=new LinkedHashMap<>();
        JSONObject staged=state.getJSONObject("pending");
        if (!current.string("webEntrypoint").equals(next.string("webEntrypoint"))) {
            if (!addressConfirmed && (staged==null || !next.string("webEntrypoint").equals(staged.getString("webEntrypoint")))) throw new IllegalArgumentException("请确认节点地址变更及重启流程");
            SettingsTransition.checkIndependent(root); SettingsTransition.checkNoTasks(metadata.scan(""));
            pending.put("webEntrypoint",next.string("webEntrypoint")); requested.put("webEntrypoint",current.string("webEntrypoint"));
        }
        if (!current.string("s3Bucket").equals(next.string("s3Bucket"))) {
            if ((!bucketConfirmed && (staged==null || !next.string("s3Bucket").equals(staged.getString("s3Bucket")))) || current.bool("s3Enabled") || next.bool("s3Enabled")) throw new IllegalArgumentException("请先保存关闭 S3，再确认桶迁移；重启后可重新开启");
            SettingsTransition.checkLocalStorage(root); SettingsTransition.checkNoTasks(metadata.scan(""));
            pending.put("s3Bucket",next.string("s3Bucket")); requested.put("s3Bucket",current.string("s3Bucket"));
        }
        // A bucket migration cannot race with re-enabling the protocol before restart.
        if (pending.containsKey("s3Bucket") && next.bool("s3Enabled"))
            throw new IllegalArgumentException("桶迁移等待重启，请先重启或取消迁移");
        JSONObject updated=JSON.parseObject(state.toJSONString()); updated.put("values",requested);
        if (pending.isEmpty()) updated.remove("pending"); else updated.put("pending",pending);
        updated.remove("transitionError"); updated.remove("migrationWarning"); updated.put("revision",revision+1);
        database.write(updated); state=updated; current=new SettingsValues(requested);
        return view();
    }
    @javax.annotation.PreDestroy @Override public void close() { database.close(); }
}
