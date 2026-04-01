package guru.springframework.springairagexpert.bootstrap;

import guru.springframework.springairagexpert.config.VectorStoreProperties;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class LoadVectorStore implements CommandLineRunner{

    @Autowired
    VectorStore vectorStore;

    @Autowired
    VectorStoreProperties  vectorStoreProperties;

    @Override
    public void run(String... args) throws Exception {
        log.debug("Loading vector store...");

        boolean needsLoading = false;
        try {
            needsLoading = vectorStore.similaritySearch("Sportsman").isEmpty();
        } catch (Exception e) {
            log.debug("Vector store collection not yet available, will load documents: {}", e.getMessage());
            needsLoading = true;
        }

        if (needsLoading) {
            log.debug("Loading documents in vector store");
            vectorStoreProperties.getDocumentsToLoad().forEach( document -> {
                log.debug("Loading document " + document.getFilename());

                TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(document);
                List<Document> documents = tikaDocumentReader.read();
                TextSplitter textSplitter = new TokenTextSplitter();
                List<Document> splitDocuments = textSplitter.apply(documents);

                vectorStore.add(splitDocuments);
            });
        }
        log.debug("Vector store loaded");
    }
}
