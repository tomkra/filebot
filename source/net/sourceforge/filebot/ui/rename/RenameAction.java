
package net.sourceforge.filebot.ui.rename;


import static java.util.Collections.*;
import static net.sourceforge.filebot.ui.NotificationLogging.*;
import static net.sourceforge.tuned.FileUtilities.*;
import static net.sourceforge.tuned.ui.TunedUtilities.*;

import java.awt.Cursor;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.SwingWorker;

import net.sourceforge.filebot.Analytics;
import net.sourceforge.filebot.ResourceManager;
import net.sourceforge.tuned.ui.ProgressDialog;
import net.sourceforge.tuned.ui.SwingWorkerPropertyChangeAdapter;
import net.sourceforge.tuned.ui.ProgressDialog.Cancellable;


class RenameAction extends AbstractAction {
	
	private final RenameModel model;
	

	public RenameAction(RenameModel model) {
		this.model = model;
		
		putValue(NAME, "Rename");
		putValue(SMALL_ICON, ResourceManager.getIcon("action.rename"));
		putValue(SHORT_DESCRIPTION, "Rename files");
	}
	

	public void actionPerformed(ActionEvent evt) {
		if (model.getRenameMap().isEmpty()) {
			return;
		}
		
		try {
			Window window = getWindow(evt.getSource());
			Map<File, File> renameMap = checkRenamePlan(validate(model.getRenameMap(), window));
			
			window.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
			RenameJob renameJob = new RenameJob(renameMap);
			renameJob.execute();
			
			try {
				// wait a for little while (renaming might finish in less than a second)
				renameJob.get(2, TimeUnit.SECONDS);
			} catch (TimeoutException ex) {
				// move/renaming will probably take a while
				ProgressDialog dialog = createProgressDialog(window, renameJob);
				dialog.setLocation(getOffsetLocation(dialog.getOwner()));
				
				// display progress dialog and stop blocking EDT
				window.setCursor(Cursor.getDefaultCursor());
				dialog.setVisible(true);
			}
		} catch (Exception e) {
			// could not rename one of the files, revert all changes
			UILogger.warning(e.getMessage());
		}
	}
	

	private Map<File, File> checkRenamePlan(List<Entry<File, File>> renamePlan) {
		// build rename map and perform some sanity checks
		Map<File, File> renameMap = new HashMap<File, File>();
		Set<File> destinationSet = new TreeSet<File>();
		
		for (Entry<File, File> mapping : renamePlan) {
			File source = mapping.getKey();
			File destination = mapping.getValue();
			
			// resolve destination
			if (!destination.isAbsolute()) {
				// same folder, different name
				destination = new File(source.getParentFile(), destination.getPath());
			}
			
			if (renameMap.containsKey(source))
				throw new IllegalArgumentException("Duplicate source file: " + source.getName());
			
			if (destinationSet.contains(destination))
				throw new IllegalArgumentException("Conflict detected: " + mapping.getValue());
			
			if (destination.exists() && !source.equals(destination))
				throw new IllegalArgumentException("File already exists: " + mapping.getValue());
			
			// use original mapping values
			renameMap.put(mapping.getKey(), mapping.getValue());
		}
		
		return renameMap;
	}
	

	private List<Entry<File, File>> validate(Map<File, String> renameMap, Window parent) {
		final List<Entry<File, File>> source = new ArrayList<Entry<File, File>>(renameMap.size());
		
		for (Entry<File, String> entry : renameMap.entrySet()) {
			source.add(new SimpleEntry<File, File>(entry.getKey(), new File(entry.getValue())));
		}
		
		List<File> destinationFileNameView = new AbstractList<File>() {
			
			@Override
			public File get(int index) {
				return source.get(index).getValue();
			}
			

			@Override
			public File set(int index, File name) {
				return source.get(index).setValue(name);
			}
			

			@Override
			public int size() {
				return source.size();
			}
		};
		
		if (ValidateDialog.validate(parent, destinationFileNameView)) {
			// names have been validated via view
			return source;
		}
		
		// return empty list if validation was cancelled
		return emptyList();
	}
	

	protected ProgressDialog createProgressDialog(Window parent, final RenameJob job) {
		final ProgressDialog dialog = new ProgressDialog(parent, job);
		
		// configure dialog
		dialog.setTitle("Moving files...");
		dialog.setIcon((Icon) getValue(SMALL_ICON));
		
		// close progress dialog when worker is finished
		job.addPropertyChangeListener(new SwingWorkerPropertyChangeAdapter() {
			
			@Override
			protected void event(String name, Object oldValue, Object newValue) {
				if (name.equals("currentFile")) {
					int i = job.renameLog.size();
					int n = job.renameMap.size();
					dialog.setProgress(i, n);
					dialog.setNote(String.format("%d of %d", i + 1, n));
				}
			}
			

			@Override
			protected void done(PropertyChangeEvent evt) {
				dialog.close();
			}
		});
		
		return dialog;
	}
	

	protected class RenameJob extends SwingWorker<Map<File, File>, Void> implements Cancellable {
		
		private final Map<File, File> renameMap;
		private final Map<File, File> renameLog;
		

		public RenameJob(Map<File, File> renameMap) {
			this.renameMap = synchronizedMap(renameMap);
			this.renameLog = synchronizedMap(new LinkedHashMap<File, File>());
		}
		

		@Override
		protected Map<File, File> doInBackground() throws Exception {
			for (Entry<File, File> mapping : renameMap.entrySet()) {
				
				if (isCancelled())
					return renameLog;
				
				// update progress dialog
				firePropertyChange("currentFile", mapping.getKey(), mapping.getValue());
				
				// rename file, throw exception on failure
				renameFile(mapping.getKey(), mapping.getValue());
				
				// remember successfully renamed matches for history entry and possible revert 
				renameLog.put(mapping.getKey(), mapping.getValue());
			}
			
			return renameLog;
		}
		

		@Override
		protected void done() {
			try {
				get(); // check exceptions
			} catch (CancellationException e) {
				// ignore
			} catch (Exception e) {
				UILogger.log(Level.SEVERE, e.getMessage(), e);
			}
			
			// collect renamed types
			List<Class> types = new ArrayList<Class>();
			
			// remove renamed matches
			for (File source : renameLog.keySet()) {
				// find index of source file
				int index = model.files().indexOf(source);
				types.add(model.values().get(index).getClass());
				
				// remove complete match
				model.matches().remove(index);
			}
			
			if (renameLog.size() > 0) {
				UILogger.info(String.format("%d files renamed.", renameLog.size()));
				HistorySpooler.getInstance().append(renameLog.entrySet());
				
				// count global statistics
				for (Class it : new HashSet<Class>(types)) {
					Analytics.trackEvent("GUI", "Rename", it.getSimpleName(), frequency(types, it));
				}
			}
		}
		

		@Override
		public boolean cancel() {
			return cancel(true);
		}
		
	}
	
}
