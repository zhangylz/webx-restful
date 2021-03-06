package com.alibaba.webx.restful.model.finder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ReflectPermission;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.webx.restful.Constants;
import com.alibaba.webx.restful.model.uri.UriComponent;
import com.alibaba.webx.restful.util.ReflectionUtils;
import com.alibaba.webx.restful.util.ResourceUtils;

public class PackageNamesScanner implements ResourceFinder {

    private final String[]                                    packages;
    private final ClassLoader                                 classloader;
    private final Map<String, UriSchemeResourceFinderFactory> finderFactories;

    private ResourceFinderStack                               resourceFinderStack;

    public PackageNamesScanner(final String[] packages){
        this(ReflectionUtils.getContextClassLoader(), ResourceUtils.getElements(packages,
                                                                                    Constants.COMMON_DELIMITERS));
    }

    public PackageNamesScanner(final ClassLoader classloader, final String[] packages){
        this.packages = packages;
        this.classloader = classloader;

        this.finderFactories = new HashMap<String, UriSchemeResourceFinderFactory>();
        add(new JarZipSchemeResourceFinderFactory());
        add(new FileSchemeResourceFinderFactory());
        add(new VfsSchemeResourceFinderFactory());

        init();
    }

    private void add(final UriSchemeResourceFinderFactory uriSchemeResourceFinderFactory) {
        for (final String s : uriSchemeResourceFinderFactory.getSchemes()) {
            finderFactories.put(s.toLowerCase(), uriSchemeResourceFinderFactory);
        }
    }

    @Override
    public boolean hasNext() {
        return resourceFinderStack.hasNext();
    }

    @Override
    public String next() {
        return resourceFinderStack.next();
    }

    @Override
    public void remove() {
        resourceFinderStack.remove();
    }

    @Override
    public InputStream open() {
        return resourceFinderStack.open();
    }

    @Override
    public void reset() {
        init();
    }

    private void init() {
        resourceFinderStack = new ResourceFinderStack();

        for (final String p : packages) {
            try {
                final Enumeration<URL> urls = ResourcesProvider.getInstance().getResources(p.replace('.', '/'),
                                                                                           classloader);
                while (urls.hasMoreElements()) {
                    try {
                        addResourceFinder(toURI(urls.nextElement()));
                    } catch (URISyntaxException e) {
                        throw new ResourceFinderException("Error when converting a URL to a URI", e);
                    }
                }
            } catch (IOException e) {
                throw new ResourceFinderException("IO error when package scanning jar", e);
            }
        }

    }

    /**
     * Find resources with a given name and class loader.
     */
    public static abstract class ResourcesProvider {

        private static volatile ResourcesProvider provider;

        private static ResourcesProvider getInstance() {
            // Double-check idiom for lazy initialization
            ResourcesProvider result = provider;

            if (result == null) { // first check without locking
                synchronized (ResourcesProvider.class) {
                    result = provider;
                    if (result == null) { // second check with locking
                        provider = result = new ResourcesProvider() {

                            @Override
                            public Enumeration<URL> getResources(String name, ClassLoader cl) throws IOException {
                                return cl.getResources(name);
                            }
                        };

                    }
                }

            }
            return result;
        }

        private static void setInstance(ResourcesProvider provider) throws SecurityException {
            SecurityManager security = System.getSecurityManager();
            if (security != null) {
                ReflectPermission rp = new ReflectPermission("suppressAccessChecks");
                security.checkPermission(rp);
            }
            synchronized (ResourcesProvider.class) {
                ResourcesProvider.provider = provider;
            }
        }

        /**
         * Find all resources with the given name using a class loader.
         * 
         * @param cl the class loader use to find the resources
         * @param name the resource name
         * @return An enumeration of URL objects for the resource. If no resources could be found, the enumeration will
         * be empty. Resources that the class loader doesn't have access to will not be in the enumeration.
         * @throws IOException if I/O errors occur
         */
        public abstract Enumeration<URL> getResources(String name, ClassLoader cl) throws IOException;
    }

    /**
     * Set the {@link ResourcesProvider} implementation to find resources.
     * <p>
     * This method should be invoked before any package scanning is performed otherwise the functionality method will be
     * utilized.
     * 
     * @param provider the resources provider.
     * @throws SecurityException if the resources provider cannot be set.
     */
    public static void setResourcesProvider(ResourcesProvider provider) throws SecurityException {
        ResourcesProvider.setInstance(provider);
    }

    private void addResourceFinder(final URI u) {
        final UriSchemeResourceFinderFactory finderFactory = finderFactories.get(u.getScheme().toLowerCase());
        if (finderFactory != null) {
            resourceFinderStack.push(finderFactory.create(u));
        } else {
            throw new ResourceFinderException("The URI scheme " + u.getScheme() + " of the URI " + u
                                              + " is not supported. Package scanning deployment is not"
                                              + " supported for such URIs."
                                              + "\nTry using a different deployment mechanism such as"
                                              + " explicitly declaring root resource and provider classes"
                                              + " using an extension of javax.ws.rs.core.Application");
        }
    }

    private URI toURI(URL url) throws URISyntaxException {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            // Work around bug where some URLs are incorrectly encoded.
            // This can occur when certain class loaders are utilized
            // to obtain URLs for resources.
            return URI.create(toExternalForm(url));
        }
    }

    private String toExternalForm(URL u) {

        // pre-compute length of StringBuffer
        int len = u.getProtocol().length() + 1;
        if (u.getAuthority() != null && u.getAuthority().length() > 0) {
            len += 2 + u.getAuthority().length();
        }
        if (u.getPath() != null) {
            len += u.getPath().length();
        }
        if (u.getQuery() != null) {
            len += 1 + u.getQuery().length();
        }
        if (u.getRef() != null) {
            len += 1 + u.getRef().length();
        }

        StringBuilder result = new StringBuilder(len);
        result.append(u.getProtocol());
        result.append(":");
        if (u.getAuthority() != null && u.getAuthority().length() > 0) {
            result.append("//");
            result.append(u.getAuthority());
        }
        if (u.getPath() != null) {
            result.append(UriComponent.contextualEncode(u.getPath(), UriComponent.Type.PATH));
        }
        if (u.getQuery() != null) {
            result.append('?');
            result.append(UriComponent.contextualEncode(u.getQuery(), UriComponent.Type.QUERY));
        }
        if (u.getRef() != null) {
            result.append("#");
            result.append(u.getRef());
        }
        return result.toString();
    }
}
