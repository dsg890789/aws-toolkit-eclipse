/*
 * Copyright 2011-2017 Amazon Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.eclipse.codecommit.explorer;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonActionProvider;

import com.amazonaws.eclipse.codecommit.CodeCommitAnalytics;
import com.amazonaws.eclipse.codecommit.CodeCommitAnalytics.EventResult;
import com.amazonaws.eclipse.codecommit.CodeCommitPlugin;
import com.amazonaws.eclipse.codecommit.wizard.CloneRepositoryWizard;
import com.amazonaws.eclipse.core.AwsToolkitCore;
import com.amazonaws.eclipse.core.regions.RegionUtils;
import com.amazonaws.eclipse.core.regions.ServiceAbbreviations;
import com.amazonaws.eclipse.explorer.ContentProviderRegistry;
import com.amazonaws.services.codecommit.AWSCodeCommit;
import com.amazonaws.services.codecommit.model.CreateRepositoryRequest;
import com.amazonaws.services.codecommit.model.DeleteRepositoryRequest;
import com.amazonaws.services.codecommit.model.RepositoryNameIdPair;
import com.amazonaws.util.StringUtils;

public class CodeCommitActionProvider extends CommonActionProvider {

    @Override
    public void fillContextMenu(IMenuManager menu) {
        StructuredSelection selection = (StructuredSelection)getActionSite().getStructuredViewer().getSelection();
        @SuppressWarnings("rawtypes")
        Iterator iterator = selection.iterator();
        boolean rootElementSelected = false;
        List<RepositoryNameIdPair> repositories = new ArrayList<RepositoryNameIdPair>();
        while ( iterator.hasNext() ) {
            Object obj = iterator.next();
            if ( obj instanceof RepositoryNameIdPair ) {
                repositories.add((RepositoryNameIdPair) obj);
            }
            if ( obj instanceof CodeCommitRootElement ) {
                rootElementSelected = true;
            }
        }

        if ( rootElementSelected && repositories.isEmpty()) {
            menu.add(new CreateRepositoryAction());
        } else if ( !rootElementSelected && !repositories.isEmpty() ) {
            if (repositories.size() == 1) {
                menu.add(new CloneRepositoryAction(repositories.get(0)));
                menu.add(new OpenRepositoryEditorAction(repositories.get(0)));
                menu.add(new DeleteRepositoryAction(repositories.get(0)));
            }
        }
    }

    private static class CreateRepositoryAction extends Action {
        public CreateRepositoryAction() {
            this.setText("Create Repository");
            this.setToolTipText("Create a secure repository to store and share your code.");
            this.setImageDescriptor(AwsToolkitCore.getDefault().getImageRegistry().getDescriptor(AwsToolkitCore.IMAGE_ADD));
        }

        @Override
        public void run() {
            NewRepositoryDialog dialog = new NewRepositoryDialog(Display.getDefault().getActiveShell());
            if (Window.OK == dialog.open()) {
                AWSCodeCommit client = CodeCommitPlugin.getCurrentCodeCommitClient();
                try {
                    client.createRepository(new CreateRepositoryRequest()
                        .withRepositoryName(dialog.getRepositoryName())
                        .withRepositoryDescription(dialog.getRepositoryDescription()));
                    ContentProviderRegistry.refreshAllContentProviders();
                    CodeCommitAnalytics.trackCreateRepository(EventResult.SUCCEEDED);

                } catch (Exception e) {
                    CodeCommitAnalytics.trackCreateRepository(EventResult.FAILED);
                    CodeCommitPlugin.getDefault().reportException("Failed to create repository!", e);
                }
            } else {
                CodeCommitAnalytics.trackCreateRepository(EventResult.CANCELED);
            }
        }

        private static class NewRepositoryDialog extends TitleAreaDialog {
            public NewRepositoryDialog(Shell parentShell) {
                super(parentShell);
            }

            private Text repositoryNameText;
            private Text repositoryDescriptionText;

            private String repositoryName;
            private String repositoryDescription;

            @Override
            public void create() {
                    super.create();
                    setTitle("Create Repository");
                    setMessage("Create a secure repository to store and share your code. "
                            + "Begin by typing a repository name and a description for your repository. "
                            + "Repository names are included in the URLs for that repository.",
                            IMessageProvider.INFORMATION);
                    getButton(IDialogConstants.OK_ID).setEnabled(false);
            }

            @Override
            protected Control createDialogArea(Composite parent) {
                    Composite area = (Composite) super.createDialogArea(parent);
                    Composite container = new Composite(area, SWT.NONE);
                    container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
                    GridLayout layout = new GridLayout(2, false);
                    container.setLayout(layout);

                    createRepositoryNameSection(container);
                    createRepositoryDescriptionSection(container);

                    return area;
            }

            private void createRepositoryNameSection(Composite container) {
                    Label repositoryNameLabel = new Label(container, SWT.NONE);
                    repositoryNameLabel.setText("Repository Name*: ");

                    GridData gridData = new GridData();
                    gridData.grabExcessHorizontalSpace = true;
                    gridData.horizontalAlignment = SWT.FILL;

                    repositoryNameText = new Text(container, SWT.BORDER);
                    repositoryNameText.setMessage("100 character limit");
                    repositoryNameText.setLayoutData(gridData);
                    repositoryNameText.addModifyListener(new ModifyListener() {
                        public void modifyText(ModifyEvent event) {
                            String inputRepositoryName = repositoryNameText.getText();
                            getButton(IDialogConstants.OK_ID).setEnabled(!StringUtils.isNullOrEmpty(inputRepositoryName));
                        }
                    });
            }

            private void createRepositoryDescriptionSection(Composite container) {
                    Label repositoryDescriptionLabel = new Label(container, SWT.NONE);
                    repositoryDescriptionLabel.setText("Repository Description: ");

                    GridData gridData = new GridData();
                    gridData.grabExcessHorizontalSpace = true;
                    gridData.horizontalAlignment = SWT.FILL;
                    repositoryDescriptionText = new Text(container, SWT.BORDER);
                    repositoryDescriptionText.setMessage("1000 character limit");
                    repositoryDescriptionText.setLayoutData(gridData);
            }

            @Override
            protected boolean isResizable() {
                    return true;
            }

            private void saveInput() {
                    repositoryName = repositoryNameText.getText();
                    repositoryDescription = repositoryDescriptionText.getText();
            }

            @Override
            protected void okPressed() {
                    saveInput();
                    super.okPressed();
            }

            public String getRepositoryName() {
                    return repositoryName;
            }

            public String getRepositoryDescription() {
                    return repositoryDescription;
            }
        }

    }

    public static class CloneRepositoryAction extends Action {
        private final RepositoryNameIdPair repository;

        public CloneRepositoryAction(RepositoryNameIdPair repository) {
            this.repository = repository;

            this.setText("Clone Repository");
            this.setToolTipText("Create a secure repository to store and share your code.");
            this.setImageDescriptor(AwsToolkitCore.getDefault().getImageRegistry().getDescriptor(AwsToolkitCore.IMAGE_EXPORT));
        }

        @Override
        public void run() {
            executeCloneAction(null, repository.getRepositoryName());
        }

        public static void executeCloneAction(AWSCodeCommit client, String repositoryName) {
            try {
                WizardDialog dialog = new WizardDialog(Display.getDefault().getActiveShell(),
                        new CloneRepositoryWizard(client, repositoryName));
                dialog.open();
            } catch (URISyntaxException e) {
                CodeCommitPlugin.getDefault().reportException(e.getMessage(), e);
            }
        }
    }

    private static class DeleteRepositoryAction extends Action {
        private final RepositoryNameIdPair repository;

        public DeleteRepositoryAction(RepositoryNameIdPair repository) {
            this.repository = repository;

            this.setText("Delete Repository");
            this.setToolTipText("Deleting this repository from AWS CodeCommit will remove the remote repository for all users.");
            this.setImageDescriptor(AwsToolkitCore.getDefault().getImageRegistry().getDescriptor(AwsToolkitCore.IMAGE_REMOVE));
        }

        @Override
        public void run() {
            Dialog dialog = new DeleteRepositoryConfirmationDialog(Display.getDefault().getActiveShell(), repository.getRepositoryName());
            if (dialog.open() != Window.OK) {
                CodeCommitAnalytics.trackDeleteRepository(EventResult.CANCELED);
                return;
            }

            Job deleteRepositoriesJob = new Job("Deleting Repository...") {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    String endpoint = RegionUtils.getCurrentRegion().getServiceEndpoints().get(ServiceAbbreviations.CODECOMMIT);
                    AWSCodeCommit codecommit = AwsToolkitCore.getClientFactory().getCodeCommitClientByEndpoint(endpoint);

                    IStatus status = Status.OK_STATUS;

                    try {
                        codecommit.deleteRepository(new DeleteRepositoryRequest().withRepositoryName(repository.getRepositoryName()));
                        CodeCommitAnalytics.trackDeleteRepository(EventResult.SUCCEEDED);
                    } catch (Exception e) {
                        status = new Status(IStatus.ERROR, CodeCommitPlugin.getDefault().getPluginId(), e.getMessage(), e);
                        CodeCommitAnalytics.trackDeleteRepository(EventResult.FAILED);
                    }

                    ContentProviderRegistry.refreshAllContentProviders();

                    return status;
                }
            };

            deleteRepositoriesJob.schedule();
        }

        private static class DeleteRepositoryConfirmationDialog extends TitleAreaDialog {
            private final String repositoryName;

            public DeleteRepositoryConfirmationDialog(Shell parentShell, String repositoryName) {
                super(parentShell);
                this.repositoryName = repositoryName;
            }

            private Text repositoryNameText;

            @Override
            public void create() {
                    super.create();
                    setTitle("Delete Repository");
                    setMessage(String.format("Delete the repository %s permanently? This cannot be undone.", repositoryName),
                            IMessageProvider.WARNING);

                    getButton(IDialogConstants.OK_ID).setEnabled(false);
            }

            @Override
            protected Control createDialogArea(Composite parent) {
                    Composite area = (Composite) super.createDialogArea(parent);
                    Composite container = new Composite(area, SWT.NONE);
                    container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
                    GridLayout layout = new GridLayout(1, false);
                    container.setLayout(layout);

                    createRepositoryNameSection(container);

                    return area;
            }

            private void createRepositoryNameSection(Composite container) {
                    new Label(container, SWT.NONE).setText("Type the name of the repository to confirm deletion:");

                    GridData gridData = new GridData();
                    gridData.grabExcessHorizontalSpace = true;
                    gridData.horizontalAlignment = SWT.FILL;

                    repositoryNameText = new Text(container, SWT.BORDER);
                    repositoryNameText.setLayoutData(gridData);
                    repositoryNameText.addModifyListener(new ModifyListener() {
                        public void modifyText(ModifyEvent event) {
                            getButton(IDialogConstants.OK_ID).setEnabled(repositoryName.equals(repositoryNameText.getText()));
                        }
                    });

                    new Label(container, SWT.NONE).setText("Are you sure you want to delete this repository permanently?");
            }

            @Override
            protected boolean isResizable() {
                    return true;
            }
        }
    }

    public static class OpenRepositoryEditorAction extends Action {
        private final RepositoryNameIdPair repository;

        public OpenRepositoryEditorAction(RepositoryNameIdPair repository) {
            this.repository = repository;

            this.setText("Open in CodeCommit Repository Editor");
        }
        @Override
        public void run() {
            String endpoint = RegionUtils.getCurrentRegion().getServiceEndpoint(ServiceAbbreviations.CODECOMMIT);
            String accountId = AwsToolkitCore.getDefault().getCurrentAccountId();

            final IEditorInput input = new RepositoryEditorInput(repository, endpoint, accountId);

            Display.getDefault().asyncExec(new Runnable() {
                public void run() {
                    try {
                        IWorkbenchWindow activeWindow = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                        activeWindow.getActivePage().openEditor(input, RepositoryEditor.ID);
                        CodeCommitAnalytics.trackOpenRepositoryEditor(EventResult.SUCCEEDED);
                    } catch (PartInitException e) {
                        CodeCommitAnalytics.trackOpenRepositoryEditor(EventResult.FAILED);
                        CodeCommitPlugin.getDefault().logError("Unable to open the AWS CodeCommit Repository Editor.", e);
                    }
                }
            });
        }
    }

}
