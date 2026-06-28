package cc.ginpika.bootfs.service.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * 读取 AI 生成图片的嵌入元数据（Stable Diffusion / ComfyUI / NovelAI 等）
 *
 * 直接解析 PNG 的 tEXt / zTXt / iTXt chunk，避免依赖第三方库的 description 格式。
 * 同时支持 A1111 文本格式和 ComfyUI JSON 格式的 prompt。
 */
@Slf4j
@Service
public class AiMetadataService {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private static final Pattern PROMPT_PATTERN =
            Pattern.compile("^(.+?)\\nNegative prompt:\\s*(.+?)(?:\\nSteps:|\\n$)", Pattern.DOTALL);
    private static final Pattern PARAM_PATTERN = Pattern.compile("(\\w+(?:\\s\\w+)*):\\s*([^,\\n]+)");

    /**
     * 提取图片中的 AI 生成元数据
     * @param filePath 图片文件路径
     * @return null 表示无 AI 元数据
     */
    public AIResult extract(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || file.length() < 8) return null;

        try {
            Map<String, String> textChunks = readPngTextChunks(file);
            if (textChunks.isEmpty()) return null;

            AIResult result = new AIResult();

            String paramsRaw = textChunks.get("parameters");
            if (paramsRaw == null) paramsRaw = textChunks.get("prompt");
            if (paramsRaw == null) paramsRaw = textChunks.get("Description");

            if (paramsRaw != null) {
                String trimmed = paramsRaw.trim();
                if (trimmed.startsWith("{")) {
                    parseComfyuiPrompt(trimmed, result);
                } else {
                    parseA1111Parameters(paramsRaw, result);
                }
            }

            return result.hasContent() ? result : null;
        } catch (Exception e) {
            log.warn("读取 AI 元数据失败: {} - {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 ComfyUI API 格式的 prompt JSON，从节点图中提取关键信息
     */
    private void parseComfyuiPrompt(String raw, AIResult result) {
        try {
            JSONObject prompt = JSONObject.parse(raw);
            Map<String, JSONObject> nodes = new HashMap<>();
            for (String id : prompt.keySet()) {
                nodes.put(id, prompt.getJSONObject(id));
            }

            // 找 KSampler 节点（核心采样器）
            for (JSONObject node : nodes.values()) {
                String classType = node.getString("class_type");
                if (classType == null) continue;

                if (classType.equals("KSampler") || classType.equals("KSamplerAdvanced")) {
                    JSONObject inputs = node.getJSONObject("inputs");
                    if (inputs != null) {
                        result.seed = getAsString(inputs, "seed");
                        result.steps = getAsString(inputs, "steps");
                        result.cfgScale = getAsString(inputs, "cfg");
                        result.sampler = getAsString(inputs, "sampler_name");

                        // 追踪 positive / negative 引用的 CLIPTextEncode 节点
                        String posNodeId = getNodeRef(inputs, "positive");
                        String negNodeId = getNodeRef(inputs, "negative");
                        if (posNodeId != null) {
                            result.positivePrompt = getClipText(nodes, posNodeId);
                        }
                        if (negNodeId != null) {
                            result.negativePrompt = getClipText(nodes, negNodeId);
                        }
                    }
                    break;
                }
            }

            // 找 CheckpointLoaderSimple 获取模型名
            for (JSONObject node : nodes.values()) {
                String classType = node.getString("class_type");
                if (classType == null) continue;
                if (classType.equals("CheckpointLoaderSimple") || classType.equals("CheckpointLoader")) {
                    JSONObject inputs = node.getJSONObject("inputs");
                    if (inputs != null) {
                        result.model = inputs.getString("ckpt_name");
                    }
                    break;
                }
            }

            // 找 EmptyLatentImage 获取尺寸
            for (JSONObject node : nodes.values()) {
                String classType = node.getString("class_type");
                if (classType == null) continue;
                if (classType.startsWith("EmptyLatentImage")) {
                    JSONObject inputs = node.getJSONObject("inputs");
                    if (inputs != null) {
                        String w = getAsString(inputs, "width");
                        String h = getAsString(inputs, "height");
                        if (w != null && h != null) {
                            result.size = w + "x" + h;
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("解析 ComfyUI prompt 失败: {}", e.getMessage());
        }
    }

    private String getClipText(Map<String, JSONObject> nodes, String nodeId) {
        JSONObject node = nodes.get(nodeId);
        if (node == null) return null;
        String classType = node.getString("class_type");
        if (!"CLIPTextEncode".equals(classType)) return null;
        JSONObject inputs = node.getJSONObject("inputs");
        if (inputs == null) return null;
        return inputs.getString("text");
    }

    private String getNodeRef(JSONObject inputs, String key) {
        Object val = inputs.get(key);
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            if (!arr.isEmpty()) return arr.getString(0);
        }
        return null;
    }

    private String getAsString(JSONObject obj, String key) {
        Object val = obj.get(key);
        if (val == null) return null;
        return val.toString();
    }

    /**
     * 解析 A1111/SD WebUI 标准的 parameters 文本
     */
    private void parseA1111Parameters(String raw, AIResult result) {
        Matcher promptMatcher = PROMPT_PATTERN.matcher(raw);
        if (promptMatcher.find()) {
            result.positivePrompt = promptMatcher.group(1).trim();
            result.negativePrompt = promptMatcher.group(2).trim();
            String paramLine = raw.substring(promptMatcher.end());
            parseParamLine(paramLine, result);
        } else {
            String[] parts = raw.split("\\nNegative prompt:", 2);
            result.positivePrompt = parts[0].trim();
            if (parts.length > 1) {
                String[] negParts = parts[1].split("\\n(?=Steps:|Sampler:|CFG|Seed:|Size:|Model)", 2);
                result.negativePrompt = negParts[0].trim();
                if (negParts.length > 1) {
                    parseParamLine(negParts[1], result);
                }
            } else {
                int lastBreak = raw.lastIndexOf('\n');
                if (lastBreak > 0) {
                    String possibleParams = raw.substring(lastBreak + 1);
                    if (possibleParams.contains("Steps:") || possibleParams.contains("Seed:")) {
                        parseParamLine(possibleParams, result);
                    }
                }
            }
        }
    }

    private void parseParamLine(String line, AIResult result) {
        Matcher matcher = PARAM_PATTERN.matcher(line);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            switch (key) {
                case "Steps": result.steps = value; break;
                case "Sampler": result.sampler = value; break;
                case "CFG scale": result.cfgScale = value; break;
                case "Seed": result.seed = value; break;
                case "Size": result.size = value; break;
                case "Model": result.model = value; break;
            }
        }
    }

    /**
     * 直接解析 PNG 文件，返回所有 tEXt / zTXt / iTXt chunk 的 key→value 映射
     */
    private Map<String, String> readPngTextChunks(File file) throws Exception {
        Map<String, String> chunks = new LinkedHashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] sig = new byte[8];
            raf.readFully(sig);
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (sig[i] != PNG_SIGNATURE[i]) return chunks;
            }

            while (raf.getFilePointer() < raf.length()) {
                long chunkStart = raf.getFilePointer();
                int length = readInt32(raf);
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);

                boolean isTextChunk = "tEXt".equals(type) || "zTXt".equals(type) || "iTXt".equals(type);
                if (!isTextChunk) {
                    raf.seek(chunkStart + 8 + length + 4);
                    if ("IEND".equals(type)) break;
                    continue;
                }

                byte[] data = new byte[length];
                raf.readFully(data);
                raf.seek(chunkStart + 8 + length + 4);

                try {
                    Map.Entry<String, String> entry = parseTextChunk(type, data);
                    if (entry != null) {
                        chunks.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                } catch (Exception e) {
                    log.debug("解析 {} chunk 失败: {}", type, e.getMessage());
                }
            }
        }
        return chunks;
    }

    private Map.Entry<String, String> parseTextChunk(String type, byte[] data) {
        if ("tEXt".equals(type)) {
            int sep = indexOf(data, (byte) 0);
            if (sep <= 0) return null;
            String key = new String(data, 0, sep, StandardCharsets.ISO_8859_1);
            String value = new String(data, sep + 1, data.length - sep - 1, StandardCharsets.UTF_8);
            return Map.entry(key, value);
        }

        if ("zTXt".equals(type)) {
            int sep = indexOf(data, (byte) 0);
            if (sep <= 0) return null;
            String key = new String(data, 0, sep, StandardCharsets.ISO_8859_1);
            int compressionMethod = data[sep + 1] & 0xFF;
            if (compressionMethod != 0) return null;
            byte[] compressed = new byte[data.length - sep - 2];
            System.arraycopy(data, sep + 2, compressed, 0, compressed.length);
            byte[] decompressed = zlibInflate(compressed);
            if (decompressed == null) return null;
            String value = new String(decompressed, StandardCharsets.UTF_8);
            return Map.entry(key, value);
        }

        if ("iTXt".equals(type)) {
            int sep = indexOf(data, (byte) 0);
            if (sep <= 0) return null;
            String key = new String(data, 0, sep, StandardCharsets.UTF_8);
            if (sep + 3 > data.length) return null;
            int compressionFlag = data[sep + 1] & 0xFF;
            int compressionMethod = data[sep + 2] & 0xFF;
            int langEnd = indexOf(data, (byte) 0, sep + 3);
            if (langEnd < 0) return null;
            int transEnd = indexOf(data, (byte) 0, langEnd + 1);
            if (transEnd < 0 || transEnd + 1 > data.length) return null;
            byte[] textBytes = new byte[data.length - transEnd - 1];
            System.arraycopy(data, transEnd + 1, textBytes, 0, textBytes.length);
            if (compressionFlag == 1) {
                if (compressionMethod != 0) return null;
                textBytes = zlibInflate(textBytes);
                if (textBytes == null) return null;
            }
            String value = new String(textBytes, StandardCharsets.UTF_8);
            return Map.entry(key, value);
        }

        return null;
    }

    private byte[] zlibInflate(byte[] compressed) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                out.write(buf, 0, n);
            }
            inflater.end();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static int indexOf(byte[] data, byte b) {
        return indexOf(data, b, 0);
    }

    private static int indexOf(byte[] data, byte b, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == b) return i;
        }
        return -1;
    }

    private static int readInt32(RandomAccessFile raf) throws Exception {
        return (raf.read() << 24) | (raf.read() << 16) | (raf.read() << 8) | raf.read();
    }

    /**
     * AI 元数据结果 — 只保留关键信息
     */
    public static class AIResult {
        public String positivePrompt;
        public String negativePrompt;
        public String model;
        public String sampler;
        public String steps;
        public String cfgScale;
        public String seed;
        public String size;

        boolean hasContent() {
            return positivePrompt != null || seed != null || model != null || steps != null;
        }
    }
}
