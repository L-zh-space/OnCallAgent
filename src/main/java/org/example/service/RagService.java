package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 结合向量检索和大语言模型生成答案
 * LLM 后端：DeepSeek（通过 OpenAI 兼容 API）
 * Embedding 后端：DashScope（text-embedding-v4）
 */
@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private ChatService chatService;

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.model:deepseek-chat}")
    private String model;

    /**
     * 流式处理用户问题（不带历史消息）
     */
    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    /**
     * 流式处理用户问题（带历史消息）
     *
     * @param question 用户问题
     * @param history  历史消息列表，格式：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
     * @param callback 流式回调接口
     */
    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            logger.info("收到 RAG 流式查询: {}", question);

            // 1. 从向量数据库检索相关文档
            List<VectorSearchService.SearchResult> searchResults =
                vectorSearchService.searchSimilarDocuments(question, topK);

            // 发送检索结果
            callback.onSearchResults(searchResults);

            if (searchResults.isEmpty()) {
                logger.warn("未找到相关文档");
                callback.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            // 2. 构建上下文和提示词
            String context = buildContext(searchResults);
            String prompt = buildPrompt(question, context);

            // 3. 构建消息列表：历史消息 + 当前问题
            List<Message> messages = new ArrayList<>();

            // 添加历史消息
            for (Map<String, String> historyMsg : history) {
                String role = historyMsg.get("role");
                String content = historyMsg.get("content");

                if ("user".equals(role)) {
                    messages.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    messages.add(new AssistantMessage(content));
                }
            }

            // 添加当前用户问题（包含 RAG 上下文）
            messages.add(new UserMessage(prompt));

            logger.debug("发送给AI模型的消息数量: {}（包含 {} 条历史消息）",
                messages.size(), history.size());

            // 4. 创建 ChatModel 并流式调用
            OpenAiApi api = chatService.createOpenAiApi();
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(0.7)
                            .maxTokens(2000)
                            .build())
                    .build();

            logger.info("开始调用 DeepSeek 模型流式接口...");

            Flux<ChatResponse> flux = chatModel.stream(new Prompt(messages));

            StringBuilder finalContent = new StringBuilder();

            logger.info("开始接收 AI 模型流式响应...");

            // Reactor Flux 不支持 blockingForEach，使用 toIterable() 进行阻塞迭代
            for (ChatResponse response : flux.toIterable()) {
                if (response.getResult() != null
                        && response.getResult().getOutput() != null
                        && response.getResult().getOutput().getText() != null) {

                    String content = response.getResult().getOutput().getText();

                    if (!content.isEmpty()) {
                        logger.debug("收到 AI 模型内容块: {}", content);
                        finalContent.append(content);
                        callback.onContentChunk(content);
                    }
                }
            }

            logger.info("AI 模型流式响应完成，总内容长度: {}", finalContent.length());

            callback.onComplete(finalContent.toString(), "");
            logger.info("已调用 onComplete 回调");

        } catch (Exception e) {
            logger.error("RAG 流式查询失败", e);
            callback.onError(e);
        }
    }

    /**
     * 构建上下文
     */
    private String buildContext(List<VectorSearchService.SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult result = searchResults.get(i);
            context.append("【参考资料 ").append(i + 1).append("】\n");
            context.append(result.getContent()).append("\n\n");
        }

        return context.toString();
    }

    /**
     * 构建提示词
     */
    private String buildPrompt(String question, String context) {
        return String.format(
            "你是一个专业的AI助手。请根据以下参考资料回答用户的问题。\n\n" +
            "参考资料：\n%s" +
            "用户问题：%s\n\n" +
            "请基于上述参考资料给出准确、详细的回答。如果参考资料中没有相关信息，请明确说明。",
            context, question
        );
    }

    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onSearchResults(List<VectorSearchService.SearchResult> results);
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        void onError(Exception e);
    }
}
