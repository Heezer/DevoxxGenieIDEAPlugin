package com.devoxx.genie.chatmodel.lmstudio;

import com.devoxx.genie.chatmodel.ChatModelFactory;
import com.devoxx.genie.model.ChatModel;
import com.devoxx.genie.model.LanguageModel;
import com.devoxx.genie.model.enumarations.ModelProvider;
import com.devoxx.genie.model.lmstudio.LMStudioModelEntryDTO;
import com.devoxx.genie.service.DevoxxGenieSettingsServiceProvider;
import com.devoxx.genie.service.LMStudioService;
import com.devoxx.genie.ui.util.NotificationUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.ProjectManager;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.localai.LocalAiChatModel;
import dev.langchain4j.model.localai.LocalAiStreamingChatModel;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LMStudioChatModelFactory implements ChatModelFactory {
    private static final Logger LOG = Logger.getInstance(LMStudioChatModelFactory.class);

    private static final ExecutorService executorService = Executors.newFixedThreadPool(5);
    private static boolean warningShown = false;
    private List<LanguageModel> cachedModels = null;
    public static final int DEFAULT_CONTEXT_LENGTH = 8000;
    private static final int CONNECTION_TIMEOUT = 5000; // 5 seconds

    @Override
    public ChatLanguageModel createChatModel(@NotNull ChatModel chatModel) {
        return LocalAiChatModel.builder()
            .baseUrl(DevoxxGenieSettingsServiceProvider.getInstance().getLmstudioModelUrl())
            .modelName(chatModel.getModelName())
            .temperature(chatModel.getTemperature())
            .topP(chatModel.getTopP())
            .maxTokens(chatModel.getMaxTokens())
            .maxRetries(chatModel.getMaxRetries())
            .timeout(Duration.ofSeconds(chatModel.getTimeout()))
            .build();
    }

    @Override
    public StreamingChatLanguageModel createStreamingChatModel(@NotNull ChatModel chatModel) {
        return LocalAiStreamingChatModel.builder()
            .baseUrl(DevoxxGenieSettingsServiceProvider.getInstance().getLmstudioModelUrl())
            .modelName(chatModel.getModelName())
            .temperature(chatModel.getTemperature())
            .topP(chatModel.getTopP())
            .timeout(Duration.ofSeconds(chatModel.getTimeout()))
            .build();
    }

    /**
     * Get the model names from the LMStudio service.
     * We're currently adding a fixed number of tokens to the model size.
     * TODO - Get the model size from the LMStudio service or have the user define them in Options panel?
     * @return List of model names
     */
    @Override
    public List<LanguageModel> getModels() {
        if (!isLMStudioRunning()) {
            NotificationUtil.sendNotification(ProjectManager.getInstance().getDefaultProject(),
                "LMStudio is not running. Please start it and try again.");
            return List.of();
        }

        if (cachedModels != null) {
            return cachedModels;
        }

        List<LanguageModel> modelNames = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            LMStudioModelEntryDTO[] lmStudioModels = LMStudioService.getInstance().getModels();
            for (LMStudioModelEntryDTO model : lmStudioModels) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    LanguageModel languageModel = LanguageModel.builder()
                        .provider(ModelProvider.LMStudio)
                        .modelName(model.getId())
                        .displayName(model.getId())
                        .inputCost(0)
                        .outputCost(0)
                        .contextWindow(DEFAULT_CONTEXT_LENGTH)
                        .apiKeyUsed(false)
                        .build();
                    synchronized (modelNames) {
                        modelNames.add(languageModel);
                    }
                }, executorService);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            cachedModels = modelNames;
        } catch (IOException e) {
            if (!warningShown) {
                NotificationUtil.sendNotification(ProjectManager.getInstance().getDefaultProject(),
                    "LMStudio is not running, please start it.");
                warningShown = true;
            }
            cachedModels = List.of();
        }
        return cachedModels;
    }

    private boolean isLMStudioRunning() {
        String baseUrl = DevoxxGenieSettingsServiceProvider.getInstance().getLmstudioModelUrl();
        try {
            URL url = new URL(baseUrl + "models");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setRequestMethod("GET");
            int responseCode = connection.getResponseCode();
            return responseCode == 200;
        } catch (Exception e) {
            LOG.warn("Failed to connect to LMStudio: " + e.getMessage());
            return false;
        }
    }
}
