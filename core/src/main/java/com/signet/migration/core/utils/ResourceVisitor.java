package com.signet.migration.core.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.sling.api.resource.AbstractResourceVisitor;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.asset.api.Asset;
import com.adobe.granite.asset.api.AssetManager;
import com.adobe.granite.asset.api.AssetVersionManager;
import com.adobe.granite.workflow.WorkflowException;
import com.day.cq.commons.jcr.JcrConstants;
import com.day.cq.commons.jcr.JcrUtil;
import com.google.common.collect.Iterators;


public class ResourceVisitor extends AbstractResourceVisitor{

	private static final Logger LOG = LoggerFactory.getLogger(ResourceVisitor.class);

	private int count = 0;

	private String nodeType = "dam:Asset";


	private static final String REL_ASSET_METADATA = "jcr:content/metadata";
	private static final String REL_ASSET_RENDITIONS = "jcr:content/renditions";
	private static final String DEFAULT_LAST_MODIFIED_BY = "Move Ecomm Product Assets Utility";
	private String lastModifiedBy = DEFAULT_LAST_MODIFIED_BY;
	public ResourceVisitor(String nodeType) {
		this.nodeType = nodeType;
	}

	/**
	 * Create getters as needed to expose state collected during the resource tree traversal.
	 *
	 * @return the number of acceptable resources visited
	 */
	public final int getCount() {
		return this.count;
	}

	/**
	 * The accept(..) method is the entry point to the visitor.
	 *
	 * Leveraging the provided accept(..) will traverse the entire resource tree.
	 *
	 * Overriding accept(..) is optional. But it can provide a handy way to reduce the traversal depth if you know
	 * certain sub-trees aren't of interest. For example, if you are traversing and collecting all Assets, you know
	 * that nothing under dam:Asset is of interest to you.
	 *
	 * @param resource the resource
	 */
	@Override
	public void accept(final Resource resource) {
		// Don't try to traverse null resources
		if (resource == null) { return; }

		// Visit the resource to work; typically the check if work should be done is in visit(..) and not in here in
		// accept(..)
		this.visit(resource);

		// Check if the current resource's sub-tree should be traversed.
		if (!StringUtils.equals(this.nodeType,resource.getValueMap().get("jcr:primaryType", String.class))) {
			// in this case, the resource's type is not a dam:Asset (so its probably a sling:OrderFolder) so keep
			// traversing to look for dam:Assets
			this.traverseChildren(resource.listChildren());
		} else {
			// The resource is a dam:Asset so we know we dont need to traverse its children as this is the lowest
			// level we want to go.
		}
	}

	/**
	 * This method is used to perform the work based on the visited resource. If possible exit quickly if no work
	 * needs to be done to increase efficiency.
	 * @param resource
	 */
	@Override
	protected void visit(final Resource resource) {
		if (StringUtils.equals(this.nodeType,resource.getValueMap().get("jcr:primaryType", String.class))) {
			LOG.info("2^^^^^resourcePath"+resource.getPath());
			try {
				execute(resource);
			} catch (Exception e) {

				StringWriter errors = new StringWriter();
				e.printStackTrace(new PrintWriter(errors));

				LOG.error(errors.toString());
			}
			this.count++;
		}
	}

	private void execute(Resource resource) throws WorkflowException {

		LOG.info("Starting Product Asset Move");
		final HashMap<String, Object> param = new HashMap<String, Object>();
		param.put(ResourceResolverFactory.SUBSERVICE, "pifservice");
		try {

			final ResourceResolver resourceResolver = resource.getResourceResolver();
			LOG.debug("Payload Path: {}", resource);
			final AssetManager assetManager = resourceResolver.adaptTo(AssetManager.class);
			final AssetVersionManager assetVersionManager = resourceResolver.adaptTo(AssetVersionManager.class);
			moveAsset(resourceResolver, assetManager, assetVersionManager, assetManager.getAsset(resource.getPath()));

			LOG.info("Asset Product Move completed");

		} catch (final Exception ex) {
			StringWriter errors = new StringWriter();
			ex.printStackTrace(new PrintWriter(errors));
			LOG.error(errors.toString());

		}


	}

	private void moveAsset(ResourceResolver resourceResolver, AssetManager assetManager,
			AssetVersionManager assetVersionManager, Asset asset) throws PersistenceException {

		LOG.info("{} moving into new location", asset.getPath());
		final String assetName = asset.getName();
		if(!StringUtils.isEmpty(assetName) && assetName.length() >= 8 && assetName.substring(0, 8).matches("^\\d+$"))
		{
			StringBuilder destinationPath = new StringBuilder(AssetMovementConstants.DEST_PATH);

			Date createdDate=asset.getValueMap().get("jcr:created",Date.class);
			Calendar calendar=new GregorianCalendar();
			calendar.setTime(createdDate);
			int year=calendar.get(Calendar.YEAR);            
			LOG.info("2^^^year"+year);

			if (assetName.length() >= 9 && assetName.substring(0, 9).matches("^\\d{9}$")) {

				destinationPath.append("/"+year+"-Akron");
				int counterVal=getCounterVal(resourceResolver,new StringBuilder(destinationPath),"folderCountProp");		
				destinationPath.append("/"+counterVal);
			} else {
				/*
				 * - check if 9th character is non-numeric; if so move to Dallas folder rather than Akron folder
				 */
				destinationPath.append("/"+year+"-Dallas");	
				int counterVal=getCounterVal(resourceResolver,new StringBuilder(destinationPath),"dallasFolderCountProp");		
				destinationPath.append("/"+counterVal);
			}


			final String destinationParentPath = destinationPath.toString();
			destinationPath.append("/").append(assetName);
			try {
				JcrUtils.getOrCreateByPath(destinationParentPath, "sling:Folder", "sling:Folder",
						resourceResolver.adaptTo(Session.class), true);

				if (!assetManager.assetExists(destinationPath.toString())) {
					assetManager.moveAsset(asset.getPath(), destinationPath.toString());
					LOG.info("{} moved into new location {}", asset.getPath(), destinationPath.toString());
				} else if (!destinationPath.toString().equals(asset.getPath())) {
					LOG.debug("Asset already exists. Creating revision {} for {} ", asset.getPath(),
							destinationPath.toString());
					createRevision(resourceResolver, assetManager, assetManager.getAsset(destinationPath.toString()),
							asset);
				} else {
					LOG.info("Asset ignored as source and destination are same {}", asset.getPath());
				}
				resourceResolver.commit();
			} catch (final Exception e) {
				LOG.error("Error in moving ecomm file {}", asset.getPath());
			}

		}
	}


	private void createRevision(ResourceResolver resourceResolver, AssetManager assetManager, Asset originalAsset,
			Asset newAsset) throws PersistenceException {
		Session session = resourceResolver.adaptTo(Session.class);

		// Create the new version
		AssetVersionManager versionManager = resourceResolver.adaptTo(AssetVersionManager.class);
		String version = getIncrementalVersion(versionManager, originalAsset);
		versionManager.createVersion(originalAsset.getPath(), version);

		String assetPath = originalAsset.getPath();

		// Delete the existing metadata and renditions from the old asset

		resourceResolver.delete(resourceResolver.getResource(assetPath + "/" + REL_ASSET_METADATA));
		resourceResolver.delete(resourceResolver.getResource(assetPath + "/" + REL_ASSET_RENDITIONS));

		try {
			Node originalAssetJcrContentNode = session
					.getNode(originalAsset.getPath() + "/" + JcrConstants.JCR_CONTENT);

			Node newAssetMetadataNode = session.getNode(newAsset.getPath() + "/" + REL_ASSET_METADATA);
			Node newAssetRenditionsNode = session.getNode(newAsset.getPath() + "/" + REL_ASSET_RENDITIONS);

			JcrUtil.copy(newAssetMetadataNode, originalAssetJcrContentNode, null);
			JcrUtil.copy(newAssetRenditionsNode, originalAssetJcrContentNode, null);

			JcrUtil.setProperty(originalAssetJcrContentNode, JcrConstants.JCR_LASTMODIFIED, new Date());
			JcrUtil.setProperty(originalAssetJcrContentNode, JcrConstants.JCR_LAST_MODIFIED_BY, lastModifiedBy);
			LOG.debug("Deleting Asset after revision {}", newAsset.getPath());
			assetManager.removeAsset(newAsset.getPath());
		} catch (RepositoryException e) {
			LOG.error("Could not create a new version of the asset", e);
			throw new PersistenceException(e.getMessage());
		}
	}



	private int getCounterVal(ResourceResolver resourceResolver, StringBuilder destinationPath, String prop) throws PersistenceException {
		int counterVal=0;
		Resource folderRes=resourceResolver.getResource(AssetMovementConstants.DEST_PATH);
		if(null!=folderRes)
		{
			if(folderRes.getValueMap().containsKey(prop))
			{
				ModifiableValueMap map=folderRes.adaptTo(ModifiableValueMap.class);
				counterVal=Integer.parseInt(map.get(prop, String.class));

				Resource counterFoldRes=resourceResolver.getResource(destinationPath.toString()+"/"+counterVal);

				if(null!=counterFoldRes)
				{
					LOG.info("2^^^^counterFoldRes"+counterFoldRes.getPath());
					Iterable<Resource> iterable= counterFoldRes.getChildren();
					LOG.info("2^^^^folderSize"+IterableUtils.size(iterable));

					int folderSize= IterableUtils.size(iterable);
					LOG.info("2**************printing the folder size"+folderSize);
					if(folderSize>=AssetMovementConstants.MAX_LIMIT)
					{
						++counterVal;
						map.put(prop,""+counterVal);
					}

				}


			}
			else
			{
				counterVal=0;
				ModifiableValueMap map=folderRes.adaptTo(ModifiableValueMap.class);
				map.put("folderCountProp",counterVal);
				map.put("dallasFolderCountProp",counterVal);
			}
			folderRes.getResourceResolver().commit();
		}

		return counterVal;
		//*****ends*******
	}

	private String getIncrementalVersion(AssetVersionManager versionManager, Asset originalAsset) {
		int size = Iterators.size(versionManager.listVersions(originalAsset.getPath()));
		return "v" + (size + 1);
	}

}
