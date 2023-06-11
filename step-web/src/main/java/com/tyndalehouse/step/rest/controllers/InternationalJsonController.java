package com.tyndalehouse.step.rest.controllers;

import com.tyndalehouse.step.core.exceptions.StepInternalException;
import com.tyndalehouse.step.core.models.ClientSession;
import com.tyndalehouse.step.core.service.LanguageService;
import com.tyndalehouse.step.rest.framework.FrontController;
import com.tyndalehouse.step.rest.framework.JsonResourceBundle;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

import static com.tyndalehouse.step.core.utils.StringUtils.isNotBlank;

/**
 * Serves the images by downloading them from a remote source if they do not already exist.
 */
@Singleton
public class InternationalJsonController extends HttpServlet {
    private static final long serialVersionUID = 1721159652548642069L;
    private static final Map<Locale, String> BUNDLES = new HashMap<Locale, String>();
    private final ObjectMapper objectMapper;
    private final Provider<ClientSession> clientSessionProvider;
    private final ModuleController modules;
    private final LanguageService languageService;

    @Inject
    public InternationalJsonController(final Provider<ObjectMapper> objectMapperProvider,
                                       final Provider<ClientSession> clientSessionProvider,
                                       final ModuleController modules,
                                       final LanguageService languageService) {
        this.clientSessionProvider = clientSessionProvider;
        this.objectMapper = objectMapperProvider.get();
        this.modules = modules;
        this.languageService = languageService;
    }
    
    @Override
    protected void doGet(final HttpServletRequest req, 
                         final HttpServletResponse response)
            throws ServletException, IOException {

        Locale locale;
        final String pathInfo = req.getPathInfo();
        String langParameter = "";
        if ((pathInfo != null) && (pathInfo.charAt(0) == '/')) {
            int pos = pathInfo.indexOf(".", 1);
            if ((pos > 2) && (pos < 7)) langParameter = pathInfo.substring(1, pos).toLowerCase();
        }
        else langParameter = req.getParameter("lang");
        if (isNotBlank(langParameter)) {
            if (langParameter.equalsIgnoreCase("zh_tw")) locale = new Locale("zh", "TW");
            else locale = new Locale(langParameter);
        } else {
            locale = clientSessionProvider.get().getLocale();
        }
        String qualifiedResponse = BUNDLES.get(locale);
        if (qualifiedResponse == null) {
            qualifiedResponse = readBundle(locale, "HtmlBundle", "InteractiveBundle");
            BUNDLES.put(locale, qualifiedResponse);
        }

        response.setCharacterEncoding(FrontController.UTF_8_ENCODING);
        response.setLocale(locale);
        response.setContentType("text/js");
        response.getOutputStream().write("window.tempVersions = ".getBytes(FrontController.UTF_8_ENCODING));
        response.getOutputStream().write(objectMapper.writeValueAsString(modules.getAllModules()).getBytes(FrontController.UTF_8_ENCODING));
        response.getOutputStream().write(";".getBytes(FrontController.UTF_8_ENCODING));
        response.getOutputStream().write("window.availLangs = ".getBytes(FrontController.UTF_8_ENCODING));
        response.getOutputStream().write(objectMapper.writeValueAsString(this.languageService.getAvailableLanguages()).getBytes(FrontController.UTF_8_ENCODING));
        response.getOutputStream().write(";".getBytes(FrontController.UTF_8_ENCODING));
        response.getOutputStream().write(qualifiedResponse.getBytes(FrontController.UTF_8_ENCODING));
        response.flushBuffer();
        response.getOutputStream().close();
    }

    /**
     * Read bundle.
     *
     * @param locale the locale
     * @return the string
     */
    private String readBundle(final Locale locale, final String... bundleNames) {
        List<ResourceBundle> bundles = new ArrayList<ResourceBundle>(bundleNames.length);
        for (String b : bundleNames) {
            bundles.add(ResourceBundle.getBundle(b, locale));
        }

        final JsonResourceBundle jsonResourceBundle = new JsonResourceBundle(bundles);
        String jsonResponse;

        try {
            jsonResponse = objectMapper.writeValueAsString(jsonResourceBundle);
        } catch (final IOException e) {
            throw new StepInternalException("Unable to read messages", e);
        }

        return "var __s = " + jsonResponse;
    }

    /**
     * Used for debugging, to reset the international JSON
     */
    public void resetCache() {
        //double check that we are actually in dev mode as well
        if(Boolean.TRUE.equals(Boolean.getBoolean("step.development"))) {
            BUNDLES.clear();
        }
    }
}
