package org.spearce.egit.ui.internal.actions;

import java.util.ArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.team.internal.ui.actions.TeamAction;
import org.spearce.egit.core.project.GitProjectData;
import org.spearce.egit.core.project.RepositoryMapping;
import org.spearce.jgit.lib.Repository;

public abstract class RepositoryAction extends TeamAction {
	/**
	 * @return repository for current project, or null
	 */
	protected Repository getRepository() {
		IResource[] selectedResources = getSelectedResources();
		
		ArrayList<IProject> projects = new ArrayList<IProject>();
		
		for (IResource resource : selectedResources) {
			IProject project = resource.getProject();
			if (!projects.contains(project))
				projects.add(project);
		}
		
		RepositoryMapping mapping = null;
		for (IProject project : projects) {
			GitProjectData tmpData = GitProjectData.get(project);
			
			RepositoryMapping repositoryMapping = tmpData.getRepositoryMapping(project);
			if (mapping == null) 
				mapping = repositoryMapping;
			else if (mapping.getRepository() != repositoryMapping.getRepository()) {
				MessageDialog.openError(getShell(), "Multiple Repositories Selection", "Cannot perform reset on multiple repositories simultaneously.\n\nPlease select items from only one repository.");
				return null;
			}
		}
		if (mapping == null) {
			MessageDialog.openError(getShell(), "Cannot Find Repository", "Could not find a repository associated with this project");
		}
		
		final Repository repository = mapping.getRepository();
		return repository;
	}

}