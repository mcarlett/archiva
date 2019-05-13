package org.apache.archiva.repository.content.maven2;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.common.filelock.FileLockException;
import org.apache.archiva.common.filelock.FileLockManager;
import org.apache.archiva.common.filelock.FileLockTimeoutException;
import org.apache.archiva.common.filelock.Lock;
import org.apache.archiva.common.utils.PathUtil;
import org.apache.archiva.configuration.FileTypes;
import org.apache.archiva.metadata.repository.storage.maven2.ArtifactMappingProvider;
import org.apache.archiva.metadata.repository.storage.maven2.DefaultArtifactMappingProvider;
import org.apache.archiva.model.ArchivaArtifact;
import org.apache.archiva.model.ArtifactReference;
import org.apache.archiva.model.ProjectReference;
import org.apache.archiva.model.VersionedReference;
import org.apache.archiva.repository.ContentNotFoundException;
import org.apache.archiva.repository.EditableManagedRepository;
import org.apache.archiva.repository.LayoutException;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.ManagedRepositoryContent;
import org.apache.archiva.repository.RepositoryException;
import org.apache.archiva.repository.content.FilesystemAsset;
import org.apache.archiva.repository.content.StorageAsset;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ManagedDefaultRepositoryContent
 */
public class ManagedDefaultRepositoryContent
    extends AbstractDefaultRepositoryContent
    implements ManagedRepositoryContent
{

    private final FileLockManager fileLockManager;

    private FileTypes filetypes;

    public void setFileTypes(FileTypes fileTypes) {
        this.filetypes = fileTypes;
    }

    private ManagedRepository repository;

    private Path repoDir;

    public ManagedDefaultRepositoryContent(ManagedRepository repository, FileTypes fileTypes, FileLockManager lockManager) {
        super(Collections.singletonList( new DefaultArtifactMappingProvider() ));
        setFileTypes( fileTypes );
        this.fileLockManager = lockManager;
        setRepository( repository );
    }

    public ManagedDefaultRepositoryContent( ManagedRepository repository, List<? extends ArtifactMappingProvider> artifactMappingProviders, FileTypes fileTypes, FileLockManager lockManager )
    {
        super(artifactMappingProviders==null ? Collections.singletonList( new DefaultArtifactMappingProvider() ) : artifactMappingProviders);
        setFileTypes( fileTypes );
        this.fileLockManager = lockManager;
        setRepository( repository );
    }

    private Path getRepoDir() {
        return repoDir;
    }


    @Override
    public void deleteVersion( VersionedReference reference )
    {
        String path = toMetadataPath( reference );
        Path projectPath = Paths.get( getRepoRoot(), path );

        Path projectDir = projectPath.getParent();
        if ( Files.exists(projectDir) && Files.isDirectory(projectDir) )
        {
            org.apache.archiva.common.utils.FileUtils.deleteQuietly( projectDir );
        }
    }

    @Override
    public void deleteProject( String namespace, String projectId )
        throws RepositoryException
    {
        ArtifactReference artifactReference = new ArtifactReference();
        artifactReference.setGroupId( namespace );
        artifactReference.setArtifactId( projectId );
        String path = toPath( artifactReference );
        Path directory = Paths.get( getRepoRoot(), path );
        if ( !Files.exists(directory) )
        {
            throw new ContentNotFoundException( "cannot found project " + namespace + ":" + projectId );
        }
        if ( Files.isDirectory(directory) )
        {
            try
            {
                org.apache.archiva.common.utils.FileUtils.deleteDirectory( directory );
            }
            catch ( IOException e )
            {
                throw new RepositoryException( e.getMessage(), e );
            }
        }
        else
        {
            log.warn( "project {}:{} is not a directory", namespace, projectId );
        }

    }

    @Override
    public void deleteArtifact( ArtifactReference artifactReference )
    {
        String path = toPath( artifactReference );
        Path filePath = Paths.get( getRepoRoot(), path );

        if ( Files.exists(filePath) )
        {
            org.apache.archiva.common.utils.FileUtils.deleteQuietly( filePath );
        }

        Path filePathmd5 = Paths.get( getRepoRoot(), path + ".md5" );

        if ( Files.exists(filePathmd5) )
        {
            org.apache.archiva.common.utils.FileUtils.deleteQuietly( filePathmd5 );
        }

        Path filePathsha1 = Paths.get( getRepoRoot(), path + ".sha1" );

        if ( Files.exists(filePathsha1) )
        {
            org.apache.archiva.common.utils.FileUtils.deleteQuietly( filePathsha1 );
        }
    }

    @Override
    public void deleteGroupId( String groupId )
        throws ContentNotFoundException
    {

        String path = StringUtils.replaceChars( groupId, '.', '/' );

        Path directory = Paths.get( getRepoRoot(), path );

        if ( Files.exists(directory) )
        {
            try
            {
                org.apache.archiva.common.utils.FileUtils.deleteDirectory( directory );
            }
            catch ( IOException e )
            {
                log.warn( "skip error deleting directory {}:", directory, e );
            }
        }
    }

    @Override
    public String getId()
    {
        return repository.getId();
    }

    @Override
    public Set<ArtifactReference> getRelatedArtifacts( ArtifactReference reference )
        throws ContentNotFoundException
    {
        Path artifactFile = toFile( reference );
        Path repoBase = PathUtil.getPathFromUri(repository.getLocation()).toAbsolutePath();
        Path repoDir = artifactFile.getParent().toAbsolutePath();

        if ( !Files.exists(repoDir))
        {
            throw new ContentNotFoundException(
                "Unable to get related artifacts using a non-existant directory: " + repoDir.toAbsolutePath() );
        }

        if ( !Files.isDirectory( repoDir ) )
        {
            throw new ContentNotFoundException(
                "Unable to get related artifacts using a non-directory: " + repoDir.toAbsolutePath() );
        }

        Set<ArtifactReference> foundArtifacts;

        // First gather up the versions found as artifacts in the managed repository.

        try (Stream<Path> stream = Files.list(repoDir)) {
            foundArtifacts = stream.filter(Files::isRegularFile).map(path -> {
                try {
                    ArtifactReference artifact = toArtifactReference(repoBase.relativize(path).toString());
                    if( artifact.getGroupId().equals( reference.getGroupId() ) && artifact.getArtifactId().equals(
                            reference.getArtifactId() ) && artifact.getVersion().equals( reference.getVersion() )) {
                        return artifact;
                    } else {
                        return null;
                    }
                } catch (LayoutException e) {
                    log.debug( "Not processing file that is not an artifact: {}", e.getMessage() );
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toSet());
        } catch (IOException e) {
            log.error("Could not read directory {}: {}", repoDir, e.getMessage(), e);
            return Collections.emptySet();
        }
        return foundArtifacts;
    }

    @Override
    public String getRepoRoot()
    {
        return convertUriToPath( repository.getLocation() );
    }

    private String convertUriToPath( URI uri ) {
        if (uri.getScheme()==null) {
            return Paths.get(uri.getPath()).toString();
        } else if ("file".equals(uri.getScheme())) {
            return Paths.get(uri).toString();
        } else {
            return uri.toString();
        }
    }

    @Override
    public org.apache.archiva.repository.ManagedRepository getRepository()
    {
        return repository;
    }

    /**
     * Gather the Available Versions (on disk) for a specific Project Reference, based on filesystem
     * information.
     *
     * @return the Set of available versions, based on the project reference.
     * @throws LayoutException
     */
    @Override
    public Set<String> getVersions( ProjectReference reference )
        throws ContentNotFoundException, LayoutException
    {
        String path = toMetadataPath( reference );

        int idx = path.lastIndexOf( '/' );
        if ( idx > 0 )
        {
            path = path.substring( 0, idx );
        }

        Path repoDir = PathUtil.getPathFromUri( repository.getLocation() ).resolve( path );

        if ( !Files.exists(repoDir) )
        {
            throw new ContentNotFoundException(
                "Unable to get Versions on a non-existant directory: " + repoDir.toAbsolutePath() );
        }

        if ( !Files.isDirectory(repoDir) )
        {
            throw new ContentNotFoundException(
                "Unable to get Versions on a non-directory: " + repoDir.toAbsolutePath() );
        }

        final String groupId = reference.getGroupId();
        final String artifactId = reference.getArtifactId();
        try(Stream<Path> stream = Files.list(repoDir)) {
            return stream.filter(Files::isDirectory).map(
                    p -> newVersionedRef(groupId, artifactId, p.getFileName().toString())
            ).filter(this::hasArtifact).map(ref -> ref.getVersion())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            log.error("Could not read directory {}: {}", repoDir, e.getMessage(), e);
        } catch (RuntimeException e) {
            if (e.getCause()!=null && e.getCause() instanceof LayoutException) {
                throw (LayoutException)e.getCause();
            } else {
                throw e;
            }
        }
        return Collections.emptySet();
    }

    static final VersionedReference newVersionedRef(final String groupId, final String artifactId, final String version) {
        VersionedReference ref = new VersionedReference();
        ref.setGroupId(groupId);
        ref.setArtifactId(artifactId);
        ref.setVersion(version);
        return ref;
    }

    @Override
    public Set<String> getVersions( VersionedReference reference )
        throws ContentNotFoundException
    {
        String path = toMetadataPath( reference );

        int idx = path.lastIndexOf( '/' );
        if ( idx > 0 )
        {
            path = path.substring( 0, idx );
        }

        Path repoBase = PathUtil.getPathFromUri(repository.getLocation());
        Path repoDir = repoBase.resolve( path );

        if ( !Files.exists(repoDir) )
        {
            throw new ContentNotFoundException(
                "Unable to get versions on a non-existant directory: " + repoDir.toAbsolutePath() );
        }

        if ( !Files.isDirectory(repoDir) )
        {
            throw new ContentNotFoundException(
                "Unable to get versions on a non-directory: " + repoDir.toAbsolutePath() );
        }

        Set<String> foundVersions = new HashSet<>();

        try(Stream<Path> stream = Files.list(repoDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> repoBase.relativize(p).toString())
                    .filter(p -> !filetypes.matchesDefaultExclusions(p))
                    .filter(filetypes::matchesArtifactPattern)
                    .map(path1 -> {
                        try {
                            return toArtifactReference(path1);
                        } catch (LayoutException e) {
                            log.debug( "Not processing file that is not an artifact: {}", e.getMessage() );
                            return null;
                        }
                    }).filter(Objects::nonNull)
                    .map(ar -> ar.getVersion())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            log.error("Could not read directory {}: {}", repoDir, e.getMessage(), e);
        }
        return Collections.emptySet();
    }

    @Override
    public boolean hasContent( ArtifactReference reference )
    {
        Path artifactFile = toFile( reference );
        return Files.exists(artifactFile) && Files.isRegularFile( artifactFile );
    }

    @Override
    public boolean hasContent( ProjectReference reference )
    {
        try
        {
            Set<String> versions = getVersions( reference );
            return !versions.isEmpty();
        }
        catch ( ContentNotFoundException | LayoutException e )
        {
            return false;
        }
    }

    @Override
    public boolean hasContent( VersionedReference reference )
    {
        try
        {
            return ( getFirstArtifact( reference ) != null );
        }
        catch ( IOException | LayoutException e )
        {
            return false;
        }
    }

    @Override
    public void setRepository( ManagedRepository repo )
    {
        this.repository = repo;
        this.repoDir = PathUtil.getPathFromUri( repository.getLocation() );
        if (repository instanceof EditableManagedRepository ) {
            ((EditableManagedRepository)repository).setContent(this);
        }

    }

    /**
     * Convert a path to an artifact reference.
     *
     * @param path the path to convert. (relative or full location path)
     * @throws LayoutException if the path cannot be converted to an artifact reference.
     */
    @Override
    public ArtifactReference toArtifactReference( String path )
        throws LayoutException
    {
        String repoPath = convertUriToPath( repository.getLocation() );
        if ( ( path != null ) && path.startsWith( repoPath ) && repoPath.length() > 0 )
        {
            return super.toArtifactReference( path.substring( repoPath.length() + 1 ) );
        }

        return super.toArtifactReference( path );
    }

    // The variant with runtime exception for stream usage
    private ArtifactReference toArtifactRef(String path) {
        try {
            return toArtifactReference(path);
        } catch (LayoutException e) {
            throw new RuntimeException(e);
        }
    }



    @Override
    public Path toFile( ArtifactReference reference )
    {
        return PathUtil.getPathFromUri( repository.getLocation()).resolve( toPath( reference ) );
    }

    @Override
    public Path toFile( ArchivaArtifact reference )
    {
        return PathUtil.getPathFromUri( repository.getLocation()).resolve( toPath( reference ) );
    }

    /**
     * Get the first Artifact found in the provided VersionedReference location.
     *
     * @param reference the reference to the versioned reference to search within
     * @return the ArtifactReference to the first artifact located within the versioned reference. or null if
     *         no artifact was found within the versioned reference.
     * @throws java.io.IOException     if the versioned reference is invalid (example: doesn't exist, or isn't a directory)
     * @throws LayoutException
     */
    private ArtifactReference getFirstArtifact( VersionedReference reference )
        throws LayoutException, IOException
    {
        String path = toMetadataPath( reference );

        int idx = path.lastIndexOf( '/' );
        if ( idx > 0 )
        {
            path = path.substring( 0, idx );
        }

        Path repoBase = PathUtil.getPathFromUri(repository.getLocation()).toAbsolutePath();
        Path repoDir = repoBase.resolve( path );

        if ( !Files.exists(repoDir) )
        {
            throw new IOException( "Unable to gather the list of snapshot versions on a non-existant directory: "
                                       + repoDir.toAbsolutePath() );
        }

        if ( !Files.isDirectory(repoDir) )
        {
            throw new IOException(
                "Unable to gather the list of snapshot versions on a non-directory: " + repoDir.toAbsolutePath() );
        }
        try(Stream<Path> stream = Files.list(repoDir)) {
            return stream.filter(Files::isRegularFile)
                .map(p -> repoBase.relativize(p).toString())
                    .filter(filetypes::matchesArtifactPattern)
                    .map(this::toArtifactRef).findFirst().orElse(null);
        } catch (RuntimeException e) {
            if (e.getCause()!=null && e.getCause() instanceof LayoutException) {
                throw (LayoutException)e.getCause();
            } else {
                throw e;
            }
        }

    }

    private boolean hasArtifact( VersionedReference reference )

    {
        try
        {
            return ( getFirstArtifact( reference ) != null );
        }
        catch ( IOException e )
        {
            return false;
        } catch (LayoutException e) {
            // We throw the runtime exception for better stream handling
            throw new RuntimeException(e);
        }
    }

    public void setFiletypes( FileTypes filetypes )
    {
        this.filetypes = filetypes;
    }


    @Override
    public void consumeData( StorageAsset asset, Consumer<InputStream> consumerFunction, boolean readLock ) throws IOException
    {
        final Path path = asset.getFilePath();
        try {
        if (readLock) {
            consumeDataLocked( path, consumerFunction );
        } else
        {
            try ( InputStream is = Files.newInputStream( path ) )
            {
                consumerFunction.accept( is );
            }
            catch ( IOException e )
            {
                log.error("Could not read the input stream from file {}", path);
                throw e;
            }
        }
        } catch (RuntimeException e)
        {
            log.error( "Runtime exception during data consume from artifact {}. Error: {}", path, e.getMessage() );
            throw new IOException( e );
        }

    }

    public void consumeDataLocked( Path file, Consumer<InputStream> consumerFunction) throws IOException
    {

        final Lock lock;
        try
        {
            lock = fileLockManager.readFileLock( file );
            try ( InputStream is = Files.newInputStream( lock.getFile()))
            {
                consumerFunction.accept( is );
            }
            catch ( IOException e )
            {
                log.error("Could not read the input stream from file {}", file);
                throw e;
            } finally
            {
                fileLockManager.release( lock );
            }
        }
        catch ( FileLockException | FileNotFoundException | FileLockTimeoutException e)
        {
            log.error("Locking error on file {}", file);
            throw new IOException(e);
        }
    }


    @Override
    public StorageAsset getAsset( String path )
    {
        final Path repoPath = getRepoDir();
        return new FilesystemAsset( repoPath, path);
    }

    @Override
    public StorageAsset addAsset( String path, boolean container )
    {
        final Path repoPath = getRepoDir();
        FilesystemAsset asset = new FilesystemAsset( repoPath, path , container);
        return asset;
    }

    @Override
    public void removeAsset( StorageAsset asset ) throws IOException
    {
        Files.delete(asset.getFilePath());
    }

    @Override
    public StorageAsset moveAsset( StorageAsset origin, String destination ) throws IOException
    {
        final Path repoPath = getRepoDir();
        boolean container = origin.isContainer();
        FilesystemAsset newAsset = new FilesystemAsset( repoPath, destination, container );
        Files.move(origin.getFilePath(), newAsset.getFilePath());
        return newAsset;
    }

    @Override
    public StorageAsset copyAsset( StorageAsset origin, String destination ) throws IOException
    {
        final Path repoPath = getRepoDir();
        boolean container = origin.isContainer();
        FilesystemAsset newAsset = new FilesystemAsset( repoPath, destination, container );
        if (Files.exists(newAsset.getFilePath())) {
            throw new IOException("Destination file exists already "+ newAsset.getFilePath());
        }
        if (Files.isDirectory( origin.getFilePath() ))
        {
            FileUtils.copyDirectory(origin.getFilePath( ).toFile(), newAsset.getFilePath( ).toFile() );
        } else if (Files.isRegularFile( origin.getFilePath() )) {
            FileUtils.copyFile(origin.getFilePath( ).toFile(), newAsset.getFilePath( ).toFile() );
        }
        return newAsset;
    }


}
