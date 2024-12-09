package com.devoxx.genie.service;

import com.devoxx.genie.model.request.ChatMessageContext;
import com.devoxx.genie.model.request.EditorInfo;
import com.devoxx.genie.model.request.SemanticFile;
import com.devoxx.genie.service.rag.SearchResult;
import com.devoxx.genie.service.rag.SemanticSearchService;
import com.devoxx.genie.ui.settings.DevoxxGenieStateService;
import com.devoxx.genie.ui.util.NotificationUtil;
import com.devoxx.genie.util.ChatMessageContextUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.langchain4j.data.message.UserMessage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.devoxx.genie.action.AddSnippetAction.SELECTED_TEXT_KEY;

/**
 * The message creation service for user and system messages.
 * Here's where also the basic prompt "engineering" is happening, including calling the AST magic.
 */
public class MessageCreationService {
    private static final Logger LOG = Logger.getLogger(MessageCreationService.class.getName());

    public static final String CONTEXT_PROMPT = "Context: \n";

    private static final String GIT_DIFF_INSTRUCTIONS = """
        Please analyze the code and provide ONLY the modified code in your response.
        Do not include any explanations or comments.
        The response should contain just the modified code wrapped in a code block using the appropriate language identifier.
        If multiple files need to be modified, provide each file's content in a separate code block.
        """;

    public static final String SEMANTIC_RESULT = """
            File: %s
            Score: %.2f
            ```java
            %s
            ```
            """;

    @NotNull
    public static MessageCreationService getInstance() {
        return ApplicationManager.getApplication().getService(MessageCreationService.class);
    }

    /**
     * Create user message.
     * @param chatMessageContext the chat message context
     * @return the user message
     */
    @NotNull
    public UserMessage createUserMessage(@NotNull ChatMessageContext chatMessageContext) {
        UserMessage userMessage;
        String context = chatMessageContext.getContext();

        if (context != null && !context.isEmpty()) {
            userMessage = constructUserMessageWithFullContext(chatMessageContext, context);
        } else {
            userMessage = constructUserMessageWithCombinedContext(chatMessageContext);
        }

        return userMessage;
    }

    private @NotNull UserMessage constructUserMessageWithCombinedContext(@NotNull ChatMessageContext chatMessageContext) {

        StringBuilder stringBuilder = new StringBuilder();

        // Add system prompt for OpenAI o1 models
        if (ChatMessageContextUtil.isOpenAIo1Model(chatMessageContext.getLanguageModel())) {
            String systemPrompt = DevoxxGenieStateService.getInstance().getSystemPrompt();
            stringBuilder.append("<SystemPrompt>").append(systemPrompt).append("</SystemPrompt>\n\n");
        }

        // If git diff is enabled, add special instructions
        if (Boolean.TRUE.equals(DevoxxGenieStateService.getInstance().getGitDiffActivated())) {
            // Git diff is enabled, add special instructions at the beginning
            stringBuilder.append("<DiffInstructions>").append(GIT_DIFF_INSTRUCTIONS).append("</DiffInstructions>\n\n");
        } else if (Boolean.TRUE.equals(DevoxxGenieStateService.getInstance().getRagActivated())) {
            // Semantic search is enabled, add search results
            String semanticContext = addSemanticSearchResults(chatMessageContext);
            if (!semanticContext.isEmpty()) {
                stringBuilder.append("<SemanticContext>\n");
                stringBuilder.append(semanticContext);
                stringBuilder.append("\n</SemanticContext>");
            }
        }

        // Add the user's prompt
        stringBuilder.append("<UserPrompt>").append(chatMessageContext.getUserPrompt()).append("</UserPrompt>\n\n");

        // Add editor content or selected text
        String editorContent = getEditorContentOrSelectedText(chatMessageContext);
        if (!editorContent.isEmpty()) {
            stringBuilder.append("<EditorContext>\n");
            stringBuilder.append(editorContent);
            stringBuilder.append("\n</EditorContext>\n\n");
        }

        UserMessage userMessage = new UserMessage(stringBuilder.toString());
        chatMessageContext.setUserMessage(userMessage);
        return userMessage;
    }

    /**
     * Create user message with project content based on semantic search results.
     * @param chatMessageContext the chat message context
     * @return the user message
     */
    private @NotNull String addSemanticSearchResults(@NotNull ChatMessageContext chatMessageContext) {
        StringBuilder contextBuilder = new StringBuilder();

        try {
            SemanticSearchService semanticSearchService = SemanticSearchService.getInstance();

            // Get semantic search results from ChromaDB
            Map<String, SearchResult> searchResults =
                    semanticSearchService.search(chatMessageContext.getProject(), chatMessageContext.getUserPrompt());

            if (!searchResults.isEmpty()) {
                List<SemanticFile> fileReferences = extractFileReferences(searchResults);

                // Store references in chat message context for UI use
                chatMessageContext.setSemanticReferences(fileReferences);

                contextBuilder.append("Referenced files:\n");
                fileReferences.forEach(file -> contextBuilder.append("- ").append(file).append("\n"));
                contextBuilder.append("\n");

                Set<Map.Entry<String, SearchResult>> entries = searchResults.entrySet();
                // Format search results
                String formattedResults = entries.stream()
                        .map(MessageCreationService::getFileContent)
                        .collect(Collectors.joining("\n"));

                contextBuilder.append(formattedResults);

                // Log the number of relevant snippets found
                NotificationUtil.sendNotification(
                        chatMessageContext.getProject(),
                        String.format("Found %d relevant project file%s using RAG", searchResults.size(), searchResults.size() > 1 ? "s" : "")
                );
            }
        } catch (Exception e) {
            LOG.warning("Failed to get semantic search results: " + e.getMessage());
        }

        return contextBuilder.toString();
    }

    private static @NotNull String getFileContent(Map.@NotNull Entry<String, SearchResult> entry) {
        String fileContent;
        try {
            fileContent = Files.readString(Paths.get(entry.getKey()));
        } catch (IOException e) {
            return "";
        }
        return SEMANTIC_RESULT.formatted(entry.getKey(), entry.getValue().score(), fileContent);
    }

    public static List<SemanticFile> extractFileReferences(@NotNull Map<String, SearchResult> searchResults) {
        return searchResults.keySet().stream()
                .map(value -> new SemanticFile(value, searchResults.get(value).score()))
                .toList();
    }

    /**
     * Get the editor content or selected text.
     * @param chatMessageContext the chat message context
     * @return the editor content or selected text
     */
    private @NotNull String getEditorContentOrSelectedText(@NotNull ChatMessageContext chatMessageContext) {
        EditorInfo editorInfo = chatMessageContext.getEditorInfo();
        if (editorInfo == null) {
            return "";
        }

        StringBuilder contentBuilder = new StringBuilder();

        // Add selected text if present
        if (editorInfo.getSelectedText() != null && !editorInfo.getSelectedText().isEmpty()) {
            contentBuilder.append("<SelectedText>\n")
                .append(editorInfo.getSelectedText())
                .append("</SelectedText>\n\n");
        }

        // Add content of selected files
        List<VirtualFile> selectedFiles = editorInfo.getSelectedFiles();
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            contentBuilder.append("<FileContents>\n");
            for (VirtualFile file : selectedFiles) {
                contentBuilder.append("File: ").append(file.getName()).append("\n")
                    .append(readFileContent(file))
                    .append("\n\n");
            }
            contentBuilder.append("\n</FileContents>\n");
        }

        return contentBuilder.toString().trim();
    }

    private @NotNull String readFileContent(@NotNull VirtualFile file) {
        try {
            return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * Construct a user message with full context.
     *
     * @param chatMessageContext the chat message context
     * @param context            the context
     * @return the user message
     */
    private @NotNull UserMessage constructUserMessageWithFullContext(@NotNull ChatMessageContext chatMessageContext,
                                                                     String context) {
        StringBuilder stringBuilder = new StringBuilder();

        // If git diff is enabled, add special instructions at the beginning
        if (Boolean.TRUE.equals(DevoxxGenieStateService.getInstance().getUseSimpleDiff())) {
            stringBuilder.append("<DiffInstructions>").append(GIT_DIFF_INSTRUCTIONS).append("</DiffInstructions>\n\n");
        }

        // Check if this is the first message in the conversation, if so add the context
        if (ChatMemoryService.getInstance().messages(chatMessageContext.getProject()).size() == 1) {
            stringBuilder.append("<Context>");
            stringBuilder.append(context);
            stringBuilder.append("</Context>\n\n");
            stringBuilder.append("=========================================\n\n");
        }

        stringBuilder.append("<UserPrompt>");
        stringBuilder.append("User Question: ");
        stringBuilder.append(chatMessageContext.getUserPrompt());
        stringBuilder.append("</UserPrompt>");

        UserMessage userMessage = new UserMessage("user_message", stringBuilder.toString());
        chatMessageContext.setUserMessage(userMessage);
        return userMessage;
    }

    /**
     * Create user prompt with context.
     *
     * @param project    the project
     * @param userPrompt the user prompt
     * @param files      the files
     * @return the user prompt with context
     */
    public @NotNull CompletableFuture<String> createUserPromptWithContextAsync(Project project,
                                                                               String userPrompt,
                                                                               @NotNull List<VirtualFile> files) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder userPromptContext = new StringBuilder();
            FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();

            for (VirtualFile file : files) {
                ApplicationManager.getApplication().runReadAction(() -> {
                    if (file.getFileType().getName().equals("UNKNOWN")) {
                        userPromptContext.append("Filename: ").append(file.getName()).append("\n");
                        userPromptContext.append("Code Snippet: ").append(file.getUserData(SELECTED_TEXT_KEY)).append("\n");
                    } else {
                        Document document = fileDocumentManager.getDocument(file);
                        if (document != null) {
                            userPromptContext.append("Filename: ").append(file.getName()).append("\n");
                            String content = document.getText();
                            userPromptContext.append(content).append("\n");
                        } else {
                            NotificationUtil.sendNotification(project, "Error reading file: " + file.getName());
                        }
                    }
                });
            }

            userPromptContext.append(userPrompt);
            return userPromptContext.toString();
        });
    }
}
