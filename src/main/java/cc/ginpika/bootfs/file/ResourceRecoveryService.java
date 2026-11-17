package cc.ginpika.bootfs.file;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Coordinates startup and periodic recovery across pending publications, albums, replicas, and deletions. */
@Service
@RequiredArgsConstructor
public class ResourceRecoveryService {
    private final ResourcePublicationService publicationService;
    private final AlbumPublicationService albumService;
    private final FileTransferService fileTransferService;
    private final ResourceReplicationService replicationService;
    private final ResourceDeletionService deletionService;
    private final ObjectKeyBootstrap objectKeyBootstrap;

    /** Called by the inventory reconciler; retries are idempotent and preserve uncertain writes. */
    public void resumePendingPublications() {
        publicationService.resumePendingPublications();
        albumService.resumePendingAlbums();
        fileTransferService.resumePendingReplicas();
        replicationService.retryReplicationJobs();
    }

    public void resumeGlobalDeletions() {
        deletionService.resumeGlobalDeletions();
    }

    public void cleanupLocalResource(String uuid) throws IOException {
        deletionService.cleanupLocalResource(uuid);
    }

    public void bootstrapExistingBindings() {
        objectKeyBootstrap.bootstrapExistingBindings();
    }
}
