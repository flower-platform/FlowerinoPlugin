package org.flowerplatform.flowerino_plugin;

import java.awt.Desktop;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.flowerplatform.flowerino_plugin.library_manager.LibraryManager;

import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.Sketch;
import processing.app.tools.Tool;
import cc.arduino.contributions.VersionHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.zafarkhaja.semver.Version;

/**
 * @author Cristian Spiescu
 */
public class FlowerinoPlugin implements Tool {

	public static final String RE_GENERATE_FROM_FLOWERINO_REPOSITORY = "(Re)generate from Flowerino Repository";

	protected ActionListener generateActionListener = new ResourceNodeRequiredActionListener(FlowerinoPlugin.this) {
		@Override
		protected void runAfterValidation() {
			if (!libraryVersionCheckedOnce.contains(resourceNodeUri)) {
				showLibraryManager(resourceNodeUri, true);
			}
				
			List<Map<String, Object>> generatedFiles = callService("templateGeneratorService/generateFiles?nodeUri=" + resourceNodeUri 
					+ "&generator=arduino&writeToDisk=false");

			// write files (with content from JSON)
			for (Map<String, Object> generatedFile : generatedFiles) {
				String fileName = StringUtils.substringAfterLast((String) generatedFile.get("fileNodeUri"), "/");
				File f = null;
				if (fileName.endsWith(".ino")) {
					// for the .ino, we use the name of the sketch, regardless of the name of the Flowerino repository
					// there shouldn't be more than 1 .ino in the list
					f = new File(editor.getSketch().getMainFilePath());
				} else {
					f = new File(editor.getSketch().getFolder(), fileName);
					if ((boolean) generatedFile.get("generateOnce") && f.exists()) {
						// if "generateOnce" and the file already exists => we skip it
						continue;
					}
				}
				try {
					BaseNoGui.saveFile((String) generatedFile.get("content"), f);
					log("File updated: " + f);
				} catch (IOException e1) {
					log("Error while saving file = " + f, e1);
				}
			}
			
			// reload project
			try {
				Method load = Sketch.class.getDeclaredMethod("load", boolean.class);
				load.setAccessible(true);
				load.invoke(editor.getSketch(), true);
			} catch (NoSuchMethodException | SecurityException
					| IllegalAccessException | IllegalArgumentException
					| InvocationTargetException e1) {
				log("Error while reloading project", e1);
			}
			log("Sketch reloaded from Flowerino repository: " + fullRepository);
		}
	};
	
	protected ActionListener downloadLibsActionListener = new ResourceNodeRequiredActionListener(FlowerinoPlugin.this) {
		@Override
		protected void runAfterValidation() {
			showLibraryManager(resourceNodeUri, false);
		}
	};
	
	protected void showLibraryManager(String resourceNodeUri, boolean showDialogOnlyIfUpdateNeeded) {
		LibraryManager lm = new LibraryManager(FlowerinoPlugin.this, resourceNodeUri);
		boolean updateNeeded = lm.refreshTable();
		
		if (showDialogOnlyIfUpdateNeeded && !updateNeeded) {
			// I'm not sure if it's necessary, being given that I didn't show it
			lm.dispose();
		} else {
			if (showDialogOnlyIfUpdateNeeded) {
				JOptionPane.showMessageDialog(null, "One or more libraries need to be updated, hence the Required Libraries window will open.");
			}
			lm.setLocationRelativeTo(null);
			lm.setVisible(true);
		}
	}
	
	protected Editor editor;
	protected String serverUrl;
	protected final static String SERVICE_PREFIX = "/ws-dispatcher";
	protected Set<String> libraryVersionCheckedOnce = new HashSet<>();
	protected String version;
	
	public Editor getEditor() {
		return editor;
	}

	public Set<String> getLibraryVersionCheckedOnce() {
		return libraryVersionCheckedOnce;
	}

	@Override
	public void init(Editor editor) {		
		// read version from file; we put it in the file to reuse it easily from ANT, when building the .jar file
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("version.txt")));
			version = r.readLine();
			r.close();
		} catch (IOException e2) {
			log("", e2);
		}
		
		// get/create global properties
		Properties globalProperties = readProperties(getGlobalPropertiesFile());
		if (globalProperties.isEmpty()) {
			globalProperties.put("serverUrl", "http://hub.flower-platform.com");
			writeProperties(globalProperties, getGlobalPropertiesFile());
		}
		serverUrl = globalProperties.getProperty("serverUrl");

		this.editor = editor;
		editor.addComponentListener(new ComponentListener() {
			@Override
			public void componentShown(ComponentEvent e) {
				log("Flowerino Plugin v" + version + " is loading. Using server URL: " + serverUrl);

				// check with version from server
				Map<String, Object> info = callService("arduinoService/getDesktopAgentInfo");
				if (info != null) {
					// may be null if server down; so don't hang here
					Version serverVersion = VersionHelper.valueOf((String) info.get("version"));
					if (serverVersion.greaterThan(VersionHelper.valueOf(version))) {
						if (JOptionPane.YES_OPTION == JOptionPane.showConfirmDialog(null, "A newer version of Flowerino Plugin is available. It's recommended to update it.\n"
								+ "Installed version = " + version
								+ ". Latest version = " + serverVersion
								+ ".\n\n"
								+ "Open the web page with download URL and instructions (external web browser)?", "Information", JOptionPane.YES_NO_OPTION)) {
							navigateUrl(serverUrl + "/servlet/public-resources/org.flowerplatform.arduino/generate/generate.html#/method-plugin");
						}
					}
				}
				
				// initialize the menu
				JMenu menu = new JMenu("Flowerino");
			    JMenuItem generateMenu = new JMenuItem(RE_GENERATE_FROM_FLOWERINO_REPOSITORY);
				menu.add(generateMenu);
				generateMenu.addActionListener(generateActionListener);
				menu.addSeparator();
				
			    JMenuItem associateMenu = new JMenuItem("Add/Edit Link to Flowerino Repository");
				menu.add(associateMenu);
				associateMenu.addActionListener(evt -> editLinkedRepository(false));
				
			    JMenuItem downloadLibs = new JMenuItem("Download Required Libs");
				menu.add(downloadLibs);
				downloadLibs.addActionListener(downloadLibsActionListener);
				menu.addSeparator();

				menu.add(new JMenuItem("Go to Diagrams: Flowerino > Linked Repository (external web browser)")).addActionListener(new ResourceNodeRequiredActionListener(FlowerinoPlugin.this) {
					@Override
					protected void runAfterValidation() {
						try {
							String[] spl = fullRepository.split("/");
							navigateUrl(serverUrl + "/#/repositories/page/" + spl[0] + URLEncoder.encode("|", "UTF-8") + spl[1] + "/diagram-editor");
						} catch (IOException e1) {
							log("Cannot open url: " + serverUrl, e1);
						}
					}
				});
				
				menu.add(new JMenuItem("Go to Flowerino > Browse Repositories (external web browser)")).addActionListener(e1 -> navigateUrl(serverUrl));
				menu.add(new JMenuItem("Go to Flowerino Web Site (external web browser)")).addActionListener(e1 -> navigateUrl("http://flower-platform.com/flowerino"));
				
				editor.getJMenuBar().add(menu, editor.getJMenuBar().getComponentCount() - 1);
				editor.getJMenuBar().revalidate();
			}
			
			@Override
			public void componentResized(ComponentEvent e) {}
			@Override
			public void componentMoved(ComponentEvent e) {}
			@Override
			public void componentHidden(ComponentEvent e) {}
		});
	}
	
	public void navigateUrl(String url) {
		try {
			Desktop.getDesktop().browse(new URI(url));
		} catch (IOException | URISyntaxException e1) {
			log("Cannot open url: " + serverUrl);
		}		
	}
	
	@Override
	public void run() {
		generateActionListener.actionPerformed(null);
	}

	@Override
	public String getMenuTitle() {
		return RE_GENERATE_FROM_FLOWERINO_REPOSITORY;
	}
	
	public static void log(String message) {
		System.out.println(message);
	}
	
	public static void log(String message, Throwable t) {
		System.out.println(message);
		t.printStackTrace(System.out);
	}
	
	@SuppressWarnings("unchecked")
	public <T> T callService(String urlWithoutPrefix) {
		URL url = null;
		BufferedReader in = null;
		try {
			url = new URL(serverUrl + SERVICE_PREFIX + "/" + urlWithoutPrefix);
			URLConnection yc = url.openConnection();
			in = new BufferedReader(new InputStreamReader(yc.getInputStream()));
			ObjectMapper objectMapper = new ObjectMapper();
			Map<String, Object> result = (Map<String, Object>) objectMapper.readValue(in, HashMap.class);
			return (T) result.get("messageResult");
		} catch (IOException e1) {
			log("Error while accessing url = " + url, e1);
			return null;
		} finally {
			IOUtils.closeQuietly(in);
		}
	}
	
	public File getProjectPropertiesFile() {
		return new File(editor.getSketch().getFolder(), ".flowerino-link");
	}
	
	public File getGlobalPropertiesFile() {
		return new File(BaseNoGui.getSketchbookFolder(), ".flowerino");
	}

	public Properties readProperties(File file) {
		Properties properties = new Properties();
		if (file.exists()) {
			InputStream is = null;
			try {
				is = new FileInputStream(file);
				properties.load(is);
			} catch (IOException e1) {
				log("Error while opening " + ".flowerino-link" + " file from " + file.getAbsolutePath(), e1);
			} finally {
				if (is != null) {
					try {
						is.close();
					} catch (IOException e1) {
						log("Error while opening " + ".flowerino-link" + " file from " + file.getAbsolutePath(), e1);
					}
				}
			}
		}
		return properties;
	}
	
	public void writeProperties(Properties properties, File file) {
		OutputStream os = null;
		try {
			os = new FileOutputStream(file);
			properties.store(os, null);
		} catch (IOException e1) {
			log("Error while saving " + ".flowerino-link" + " file in " + file.getAbsolutePath(), e1);
		} finally {
			try {
				os.close();
			} catch (IOException e1) {
				log("Error while saving " + ".flowerino-link" + " file in " + file.getAbsolutePath(), e1);
			}
		}
		log("Config info successfully saved in " + file.getAbsolutePath());
	}
	
	public String getResourceNodeUri(String fullRepository) {
		if (fullRepository == null || fullRepository.isEmpty()) {
			return null;
		}
		String repository = StringUtils.substringAfter(fullRepository, "/");
		return "fpp:" + fullRepository + "|" + repository + ".flower-platform";
	}
	
	public String editLinkedRepository(boolean showAdditionalText) {
		Properties properties = readProperties(getProjectPropertiesFile());
		String message = "Full repository name from Flowerino (e.g. myUser/myRepo)";
		if (showAdditionalText) {
			message = "Before continuing, please link this project with a Flowerino repository.\n\n" + message;
		}
		
		String result = JOptionPane.showInputDialog(message, properties.get("fullRepository"));
		if (result == null) {
			return null;
		}
		properties.put("fullRepository", result);

		writeProperties(properties, getProjectPropertiesFile());
		return result;
	}
	
}