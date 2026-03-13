package cc.ginpika.bootfs.service.meilisearch;

import com.alibaba.fastjson2.JSONObject;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;
import cc.ginpika.bootfs.config.MeiliSearchConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
public class MeiliSearchService {
    @Autowired
    MeiliSearchConfig meiliSearchConfig;

    private Client client;

    @EventListener
    public void onRefresh(ContextRefreshedEvent event) {
        String hostUrl = meiliSearchConfig.getUrl();
        String masterKey = meiliSearchConfig.getMasterKey();
        client = new Client(new Config(hostUrl, masterKey));
    }

    public void addToFullText(String jsonString) {
        Index index = client.index("full-text");
        index.addDocuments(jsonString);
    }

    public void addToFullText(FullTextDocument fullTextDocument) {
        addToFullText(JSONObject.toJSONString(fullTextDocument));
    }

    public void addToImageHost(ImageHostDocument imageHostDocument) {
        Index index = client.index("image-host");
        index.addDocuments(JSONObject.toJSONString(imageHostDocument));
    }

    public String querySetu() {
        try {
            Index index = client.index("image-host");
            SearchResult searchResult = (SearchResult) index.search(new SearchRequest("")
                    .setLimit(Integer.MAX_VALUE));
            List<String> setuList = new ArrayList<>();
            if (searchResult != null && searchResult.getHits() != null) {
                searchResult.getHits().forEach(hit -> {
                    String url = (String) hit.get("poster");
                    if (StringUtils.isNotBlank(url)) {
                        setuList.add(url);
                    }
                });
            }
            if (setuList.isEmpty()) {
                log.warn("No public images found in image-host index");
                return null;
            }
            int lucky = new Random().nextInt(setuList.size());
            return setuList.get(lucky);
        } catch (Exception e) {
            log.error("Failed to query setu from MeiliSearch", e);
            return null;
        }
    }
}
