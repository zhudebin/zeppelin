package com.nflabs.zeppelin.driver;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.ArrayUtils;
import org.apache.log4j.Logger;
import org.reflections.Reflections;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.nflabs.zeppelin.conf.ZeppelinConfiguration;
import com.nflabs.zeppelin.driver.mock.MockDriver;

/**
 * ZeppelinDriverFactory loads drivers from driver dir.
 * Factory creates separate classloader for each subdirectory of driver directory.
 * Therefore each driver implementation can avoid class confliction.
 * 
 * Factory scans all class to find subclass of ZeppelinDriver.
 * One driver found, a instance created an being ready to create connection;
 * 
 * @author moon
 *
 */
public class ZeppelinDriverFactory {
	Logger logger = Logger.getLogger(ZeppelinDriverFactory.class);
	
	List<ZeppelinDriver> drivers = new LinkedList<ZeppelinDriver>();
	Map<String, String> uris = new HashMap<String, String>();
	String defaultDriverName = null;
	
	public ZeppelinDriverFactory(ZeppelinConfiguration conf, String driverRootDir, URI [] uriList) throws ZeppelinDriverException{
		if (driverRootDir==null || uriList==null) {
			return;
		}
		
		File root = new File(driverRootDir);
		File[] drivers = root.listFiles();
		if (drivers!=null) {
			for (File d : drivers) {
				logger.info("Load driver "+d.getName()+" from "+d.getAbsolutePath());;
				loadLibrary(d);
			}
		}
		
		if (uriList!=null) {
			for (URI uri : uriList) {
				uris.put(uri.getScheme(), uri.getSchemeSpecificPart());
				if (defaultDriverName == null) {
					defaultDriverName = uri.getScheme();
				}
			}
		}
	}
	
	public Collection<String> getAllConfigurationNames(){
		return uris.keySet();
	}
	
	public String getDefaultConfigurationName(){
		return defaultDriverName;
	}
	
	public String getUrlFromConfiguration(String configurationName){
		return uris.get(configurationName);
	}
	
	private URLClassLoader loadLibrary(File path){
		ClassLoader oldcl = Thread.currentThread().getContextClassLoader();
		URL[] urls;
		try {
			urls = recursiveBuildLibList(path);

			URLClassLoader cl = new URLClassLoader(urls, oldcl);
			Thread.currentThread().setContextClassLoader(cl);
			
			Set<String> packages = new HashSet<String>();
			ImmutableSet<ClassInfo> classes = ClassPath.from(cl).getTopLevelClasses();
			
			for(ClassInfo c : classes) {
				int p = c.getName().indexOf(".");
				if(p>0){
					String rootPkgName = c.getName().substring(0, p);
					if(packages.contains(rootPkgName)==false){
						packages.add(rootPkgName);
					}
					
				}
			}

			Reflections reflections = new Reflections(packages);
			Set<Class<? extends ZeppelinDriver>> driverClasses = reflections.getSubTypesOf(ZeppelinDriver.class);
			for(Class c : driverClasses){
				if(MockDriver.class.isAssignableFrom(c)==false){
					logger.info("Found driver "+c.getName()+" cl="+cl);
					Constructor<ZeppelinDriver> constructor = c.getConstructor(new Class []{});
					ZeppelinDriver driver = constructor.newInstance();
					driver.setClassLoader(cl);
					driver.init();
					drivers.add(driver);
					
				}
			}			
			return cl;
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			Thread.currentThread().setContextClassLoader(oldcl);	
		}
		
		return null;
	}
	
	private URL [] recursiveBuildLibList(File path) throws MalformedURLException{
		URL [] urls = new URL[0];
		if (path.exists()==false){ 
			return urls;
		} else if (path.getName().startsWith(".")) {
			return urls;
		} else if (path.isDirectory()) {
			File[] files = path.listFiles();			
			if (files!=null) {				
				for (File f : files) {
					urls = (URL[]) ArrayUtils.addAll(urls, recursiveBuildLibList(f));
				}
			}
			return urls;
		} else {
			return new URL[]{path.toURI().toURL()};
		}
	}
	
	/**
	 * Create new driver instance
	 * @param name friendly name of driver configuration
	 * @return driver instance
	 * @throws ZeppelinDriverException
	 */
	public ZeppelinDriver getDriver(String name) throws ZeppelinDriverException{
		String uri = uris.get(name);
		try {
			return getDriverByUrl(uri);
		} catch (Exception e) {
			throw new ZeppelinDriverException(e);
		}
	}
	
	/**
	 * Create new driver instance
	 * @param uri eg) hive://localhost:10000/default
	 * @return
	 */
	private ZeppelinDriver getDriverByUrl(String uri) throws ZeppelinDriverException {
		for (ZeppelinDriver d : drivers) {
			if(d.acceptsURL(uri)){
				return d;
			}
		}
		throw new ZeppelinDriverException("Can't find driver for "+uri);
	}	
}
