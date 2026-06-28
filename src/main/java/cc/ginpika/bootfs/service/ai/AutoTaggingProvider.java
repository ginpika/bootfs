package cc.ginpika.bootfs.service.ai;

import cc.ginpika.bootfs.domain.dto.Tag;

import java.util.Collections;
import java.util.List;

/**
 * AI 自动标签提供者接口。
 * 未来接入视觉模型（CLIP、Gemini Vision 等）时实现此接口，
 * 为文件自动生成标签建议。
 */
public interface AutoTaggingProvider {

    /**
     * 分析文件并返回建议标签
     *
     * @param filePath     文件路径（图片/视频）
     * @param existingTags 现有手动标签，AI 可参考避免重复
     * @return 建议标签列表，source 应为 "ai"，confidence 为置信度
     */
    List<Tag> analyze(String filePath, List<Tag> existingTags);

    /**
     * 检查该 provider 是否可用（如 API key 已配置、模型已就绪）
     */
    default boolean isAvailable() {
        return false;
    }

    /**
     * 返回空实现的默认 Provider
     */
    static AutoTaggingProvider noop() {
        return new AutoTaggingProvider() {
            @Override
            public List<Tag> analyze(String filePath, List<Tag> existingTags) {
                return Collections.emptyList();
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }
}
