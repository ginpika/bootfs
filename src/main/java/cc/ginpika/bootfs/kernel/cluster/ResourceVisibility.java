package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

/** Shared deletion state applies immediately, even before an owner's physical cleanup. */
@Component
public class ResourceVisibility {
    private final MetadataStore metadata;
    public ResourceVisibility(MetadataStore metadata) { this.metadata=metadata; }
    public boolean deleted(String id) {
        if (id==null || !id.matches("[A-Za-z0-9_-]+")) return true;
        if (metadata.get("/tombstones/"+id)!=null) return true;
        String raw=metadata.get("/documents/full-text/"+id);
        if (raw==null) return false;
        JSONObject doc=JSONObject.parseObject(raw);
        if (doc.getBooleanValue("_bootfsDeleted")) return true;
        String parent=doc.getString("parent"), original=doc.getString("copyOf");
        return parent!=null && metadata.get("/tombstones/"+parent)!=null || original!=null && metadata.get("/tombstones/"+original)!=null;
    }
}
