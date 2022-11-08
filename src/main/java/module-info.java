module com.github.andirady.jlangd {
    requires java.logging;
    requires java.compiler;
    requires jdk.compiler;
    requires jdk.zipfs;
    requires com.google.gson;
    requires org.eclipse.lsp4j;
    requires org.eclipse.lsp4j.jsonrpc;

    uses com.github.andirady.jlangd.ProjectReader;
    provides com.github.andirady.jlangd.ProjectReader
        with com.github.andirady.jlangd.MavenProjectReader, 
             com.github.andirady.jlangd.AntProjectReader;
}
