package com.github.andirady.jlangd;

import java.io.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.FileHandler;
import java.util.logging.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public class Main implements LanguageServer, LanguageClientAware {

    private static final String SERVER_NAME = "jlangd";
    private static final String SERVER_VERSION = "1";
    
    private static final Logger LOG = Logger.getLogger("main");

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            for (var i = 0; i < args.length - 1;) {
                var key = args[i];
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException("Key must starts with '--'");
                }
                System.setProperty(key.replaceFirst("--", ""), args[++i]);
            }
        }

        var formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                var s = String.format("%s %s [%s] %s %s %s%n", record.getInstant().toEpochMilli(),
                                                               record.getLevel(),
                                                               Thread.currentThread().getName(),
                                                               record.getLoggerName(),
                                                               record.getSourceMethodName(),
                                                               record.getMessage());
                if (record.getThrown() != null) {
                    try (
                        var baos = new ByteArrayOutputStream();
                        var out = new PrintStream(baos);
                    ) {
                        record.getThrown().printStackTrace(out);
                        s += baos;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                return s;
            }
        };

        var fileHandlerLevel = Level.parse(System.getProperty("fileHandler.level", "OFF"));
        
        if (fileHandlerLevel != Level.OFF) {
            var fileHandler = new FileHandler(SERVER_NAME + "-%u.log");
            fileHandler.setFormatter(formatter);

            var rootLogger = Logger.getLogger("");
            rootLogger.addHandler(fileHandler);
            rootLogger.setLevel(fileHandlerLevel);
        }

        LOG.fine(() -> "Using JAVA_HOME=" + System.getProperty("java.home"));

        try {
            var textDocService = new JavaTextDocumentService();
            var server = new Main(textDocService);
            var launcher = new LSPLauncher.Builder<LanguageClient>()
                                          .setLocalService(server)
                                          .setRemoteInterface(LanguageClient.class)
                                          .setInput(System.in)
                                          .setOutput(System.out)
                                          .create();
            server.connect(launcher.getRemoteProxy());
            
            launcher.startListening();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }
    
    private final JavaTextDocumentService textDocumentService;
    
    public Main(JavaTextDocumentService textDocService) {
        this.textDocumentService = textDocService;
    }

    @Override
    public void connect(LanguageClient client) {
        textDocumentService.connect(client);
    }

    @SuppressWarnings("deprecation")
    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        LOG.fine(params::toString);

        return CompletableFuture.supplyAsync(() -> {
            LOG.info("Initializing...");
            var t0 = System.currentTimeMillis();
            var folders = params.getWorkspaceFolders();
            if (folders == null) {
                folders = List.of(new WorkspaceFolder(params.getRootUri(), UUID.randomUUID().toString()));
            }

            folders.parallelStream().map(WorkspaceFolder::getUri).map(Projects::forUri)
                   .forEach(p -> p.setDiagnosticsConsumer(textDocumentService));

            var serverCaps = new ServerCapabilities();
            var syncOptions = new TextDocumentSyncOptions();
            syncOptions.setChange(TextDocumentSyncKind.Incremental);
            syncOptions.setSave(true);
            serverCaps.setTextDocumentSync(syncOptions);
            serverCaps.setCompletionProvider(new CompletionOptions(true, List.of(".", ":")));
            var resp = new InitializeResult(serverCaps, new ServerInfo(SERVER_NAME, SERVER_VERSION));
            LOG.info(() -> "Initialized in " + (System.currentTimeMillis() - t0) + " ms.");

            return resp;
        });
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        Projects.closeAll();
        return CompletableFuture.supplyAsync(() -> null);
    }

    @Override
    public void exit() {
        ForkJoinPool.commonPool().shutdownNow();
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new JavaWorkspaceService();
    }
}
