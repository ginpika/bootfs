package cc.ginpika.bootfs.kernel.cluster;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads preserved historical versions; federation never merges resource databases. */
public final class ResourceVersions {
    private ResourceVersions() {}
    public static NavigableMap<String,String> versions(Map<String,String> state,String namespace,String key) {
        String prefix="/versions/"+namespace+"/"+Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8))+"/";
        NavigableMap<String,String> result=new TreeMap<>();
        state.forEach((name,value) -> { if (name.startsWith(prefix)) result.put(name,value); }); return result;
    }
}
