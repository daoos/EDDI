package ai.labs.resources.impl.packages.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.impl.utilities.ResourceUtilities;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.packages.IPackageStore;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.URIUtilities;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.inject.Inject;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class PackageStore implements IPackageStore {
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final PackageHistorizedResourceStore packageResourceStore;

    @Inject
    public PackageStore(MongoDatabase database, IDocumentBuilder documentBuilder, IDocumentDescriptorStore documentDescriptorStore) {
        this.documentDescriptorStore = documentDescriptorStore;
        RuntimeUtilities.checkNotNull(database, "database");

        final String collectionName = "packages";
        PackageMongoResourceStorage mongoResourceStorage =
                new PackageMongoResourceStorage(database, collectionName, documentBuilder, PackageConfiguration.class);
        packageResourceStore = new PackageHistorizedResourceStore(mongoResourceStorage);
    }

    @Override
    public IResourceId create(PackageConfiguration packageConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(), "packageExtensions");
        return packageResourceStore.create(packageConfiguration);
    }

    @Override
    public PackageConfiguration read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return packageResourceStore.read(id, version);
    }

    @Override
    public Integer update(String id, Integer version, PackageConfiguration packageConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(packageConfiguration.getPackageExtensions(), "packageExtensions");
        return packageResourceStore.update(id, version, packageConfiguration);
    }

    @Override
    public void delete(String id, Integer version) throws ResourceModifiedException, ResourceNotFoundException {
        packageResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        packageResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return packageResourceStore.getCurrentResourceId(id);
    }

    @Override
    public List<DocumentDescriptor> getPackageDescriptorsContainingResource(String resourceURI,
                                                                            boolean includePreviousVersions)
            throws ResourceStoreException, ResourceNotFoundException {

        List<DocumentDescriptor> ret = new LinkedList<>();

        int startIndexVersion = resourceURI.lastIndexOf("=") + 1;
        Integer version = Integer.parseInt(resourceURI.substring(startIndexVersion));
        String resourceURIPart = resourceURI.substring(0, startIndexVersion);

        do {
            resourceURI = resourceURIPart + version;
            List<IResourceId> packagesContainingResource =
                    packageResourceStore.getPackageDescriptorsContainingResource(resourceURI);
            for (IResourceId packageId : packagesContainingResource) {

                if (packageId.getVersion() < getCurrentResourceId(packageId.getId()).getVersion()) {
                    continue;
                }

                boolean alreadyContainsResource = !ret.stream().filter(
                        resource ->
                        {
                            String id = URIUtilities.extractResourceId(resource.getResource()).getId();
                            return id.equals(packageId.getId());
                        }).
                        findFirst().isEmpty();

                if (alreadyContainsResource) {
                    continue;
                }

                ret.add(documentDescriptorStore.readDescriptor(
                        packageId.getId(),
                        packageId.getVersion()));
            }

            version--;
        } while (includePreviousVersions && version >= 1);

        return ret;
    }

    private class PackageMongoResourceStorage extends MongoResourceStorage<PackageConfiguration> {
        PackageMongoResourceStorage(MongoDatabase database, String collectionName, IDocumentBuilder documentBuilder, Class<PackageConfiguration> documentType) {
            super(database, collectionName, documentBuilder, documentType);
        }

        List<IResourceId> getPackageDescriptorsContainingResource(String resourceURI) throws ResourceNotFoundException {
            String searchQuery = String.format("JSON.stringify(this).indexOf('%s')!=-1", resourceURI);
            Document filter = new Document("$where", searchQuery);

            return ResourceUtilities.getAllConfigsContainingResources(filter,
                    currentCollection, historyCollection, documentDescriptorStore);
        }
    }

    private class PackageHistorizedResourceStore extends HistorizedResourceStore<PackageConfiguration> {
        private final PackageMongoResourceStorage resourceStorage;

        PackageHistorizedResourceStore(PackageMongoResourceStorage resourceStorage) {
            super(resourceStorage);
            this.resourceStorage = resourceStorage;
        }

        List<IResourceId> getPackageDescriptorsContainingResource(String resourceURI)
                throws ResourceNotFoundException {
            return resourceStorage.getPackageDescriptorsContainingResource(resourceURI);
        }
    }
}
