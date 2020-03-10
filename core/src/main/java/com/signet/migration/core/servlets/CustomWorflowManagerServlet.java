package com.signet.migration.core.servlets;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.asset.api.AssetManager;
import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.model.WorkflowModel;
import com.day.cq.dam.commons.util.DamUtil;
import com.day.cq.workflow.WorkflowService;
import com.signet.migration.core.utils.AssetMovementConstants;
import com.signet.migration.core.utils.ResourceVisitor;

/**
 * @author monalisa 
 *signet-asset-migration.core
 *10:03:40 AM 2019
 */
@Component(service=Servlet.class,property={
		Constants.SERVICE_DESCRIPTION + "=Signet Custom Worflow Trigger Servlet",
		"sling.servlet.paths="+"/bin/signetWorflowTrigger",
		"sling.servlet.methods=" + HttpConstants.METHOD_POST,
})
public class CustomWorflowManagerServlet extends SlingAllMethodsServlet{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private static final Logger log = LoggerFactory.getLogger(CustomWorflowManagerServlet.class);	 



	@Reference
	public ResourceResolverFactory resolverFactory;
	@Reference
	private WorkflowService workflowService;
	private WorkflowSession workflowSession;
	private Session session;
	private ResourceResolver resourceResolver;
	private String action="";
	

	private HashMap<String,String> failedPayloads;
	private ArrayList<String> successPayloads;
	private ArrayList<File> downloadFiles;
	private String failedFileName="Failed_Report.txt";
	private String successFileName="Success_Report.txt";
	private String zipName="Report.zip";

	private static final String DELETE_ACTION="delete";
	private static final String MOVE_ACTION="move";
	private static final String NODE_TYPE="dam:Asset";
	
	@Override
	protected void doPost(final SlingHttpServletRequest request,final SlingHttpServletResponse resp) throws ServletException, IOException {

		try {
			resourceResolver=request.getResourceResolver();
			session=resourceResolver.adaptTo(Session.class);
			workflowSession=request.getResourceResolver().adaptTo(WorkflowSession.class);
			action=request.getParameter("action").trim();
			
			
			if(null!=action && action.equals(MOVE_ACTION))
			{
				AssetMovementConstants.SRC_PATH=request.getParameter("source").trim();
				AssetMovementConstants.DEST_PATH=request.getParameter("destn").trim();
				downloadFiles=new ArrayList<File>();
				moveAssets();
				createSuccessReport();
				createFailedReport();
				prepareDownloadResponse(resp);
			}
			else
			{
				byte[] content=request.getParameter("fileupload").getBytes();
				downloadFiles=new ArrayList<File>();
				failedPayloads=new HashMap<String,String>();
				successPayloads=new ArrayList<String>();
				log.info("line no 96"+action);
				processfile(content);
				createSuccessReport();
				createFailedReport();
				prepareDownloadResponse(resp);
			}
			
			
			

		} catch (Exception ex) {

			StringWriter errors = new StringWriter();
			ex.printStackTrace(new PrintWriter(errors));

			log.error(errors.toString());
		}
		finally
		{
			if(null!=action && action.equals(MOVE_ACTION))
			{
				resp.sendRedirect("/etc/dam/signet-dam/asset-movement.html");
			}
			else
			{
				resp.sendRedirect("/etc/dam/signet-dam/dam-workflow-trigger.html");
			}
			
		}


	}

	private void prepareDownloadResponse(SlingHttpServletResponse response) throws IOException {

		File zipFile=createZip();
		response.setContentType("application/zip");
		response.setContentLength((new Long(zipFile.length())).intValue());
		response.setHeader("Content-Disposition","attachment;filename=\"" + zipFile.getName() + "\"");

		byte[] arBytes = new byte[(int)zipFile.length()];
		FileInputStream is = new FileInputStream(zipFile);
		is.read(arBytes);
		ServletOutputStream op = response.getOutputStream();
		op.write(arBytes);
		op.flush();
		op.close();	
		is.close();
		//deleting files created
		zipFile.delete();

	}

	private File createZip() throws IOException {

		File zipFile=new File(zipName);
		FileOutputStream fos = new FileOutputStream(zipFile);
		ZipOutputStream zos = new ZipOutputStream(fos);
		for(File file:downloadFiles)
		{
			log.info("2^^filename"+file.getName());
			FileInputStream fis = new FileInputStream(file);
			ZipEntry zipEntry = new ZipEntry(file.getName());
			zos.putNextEntry(zipEntry);

			byte[] bytes = new byte[1024];
			int length;
			while ((length = fis.read(bytes)) >= 0) {
				zos.write(bytes, 0, length);
			}

			zos.closeEntry();
			fis.close();
			file.delete();
		}
		fos.close();
		return zipFile;

	}

	private void createFailedReport() throws IOException{

		if(null!=failedPayloads&&failedPayloads.size()>0)
		{
			StringBuffer fileContent=new StringBuffer();
			failedPayloads.forEach((payload, message) -> fileContent.append((payload + ":" + message+System.lineSeparator())));
			writeToFile(failedFileName, fileContent);
		}
		else if(AssetMovementConstants.failedPayloads.size()>0)
		{
			StringBuffer fileContent=new StringBuffer();
			AssetMovementConstants.failedPayloads.forEach((payload, message) -> fileContent.append((payload + ":" + message+System.lineSeparator())));
			writeToFile(failedFileName, fileContent);
		}
	}

	private void createSuccessReport() throws IOException {
		if(null!=successPayloads && successPayloads.size()>0)
		{
			StringBuffer fileContent=new StringBuffer();
			successPayloads.forEach(payload -> fileContent.append(payload+System.lineSeparator()));
			writeToFile(successFileName, fileContent);
		}
		else if(AssetMovementConstants.successPayloads.size()>0)
		{
			StringBuffer fileContent=new StringBuffer();
			AssetMovementConstants.successPayloads.forEach(payload -> fileContent.append(payload+System.lineSeparator()));
			writeToFile(successFileName, fileContent);
		}

	}

	private void writeToFile(String fileName, StringBuffer fileContent) throws IOException {

		log.info("in write"+fileContent.toString());
		File file=new File(fileName);
		file.createNewFile();
		Writer writer = null;
		writer = new BufferedWriter(new OutputStreamWriter( new FileOutputStream(file), "utf-8"));
		writer.write(fileContent.toString());
		writer.close();			
		downloadFiles.add(file);
	}

	private void processfile(byte[] content) throws IOException{
		InputStream inputStream=null;
		BufferedReader bufferedReader=null;			
		inputStream=new ByteArrayInputStream(content);
		bufferedReader=new BufferedReader(new InputStreamReader(inputStream));
		String line=null;
		while((line=bufferedReader.readLine())!=null)
		{
			log.info("payload path"+line);
			callAction(line.trim());


		}
	}

	private void callAction(String payload) {
		try
		{
			if(session.itemExists(payload))
			{

				if(null!=action && action.equals(DELETE_ACTION))
				{
					deleteNodes(payload);
				}
				else
				{
					triggerWorkflow(payload);	
				}
				successPayloads.add(payload);
			}
			else
			{
				failedPayloads.put(payload, "Item doesn't Exist");
			}
		}
		catch(Exception excep)
		{
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			excep.printStackTrace(pw);
			String sStackTrace = sw.toString(); // stack trace as a string
			
			failedPayloads.put(payload, sStackTrace);
		}

	}

	private void moveAssets() {
		ResourceVisitor resVis=new ResourceVisitor(NODE_TYPE);
		resVis.accept(resourceResolver.getResource(AssetMovementConstants.SRC_PATH));
		
	}

	private void deleteNodes(String payload) throws PathNotFoundException, RepositoryException
	{

		Resource resource=resourceResolver.getResource(payload);
		if(DamUtil.isAsset(resource))
		{
            log.info("removing asset"+payload);
			AssetManager assetManager=resourceResolver.adaptTo(AssetManager.class);
            assetManager.removeAsset(payload);
		}
		else
		{
			log.info("removing node"+payload);
			Node node=session.getNode(payload);
			node.remove();
		}
        session.save();
	}

	private void triggerWorkflow(String payload) throws WorkflowException
	{
		WorkflowModel workflowModel=workflowSession.getModel(action);
		WorkflowData workflowData=workflowSession.newWorkflowData("JCR_PATH", payload);
		workflowSession.startWorkflow(workflowModel, workflowData);
		
	}

}


