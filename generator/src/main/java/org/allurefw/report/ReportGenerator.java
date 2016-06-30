package org.allurefw.report;

import com.google.common.reflect.ClassPath;
import com.google.inject.Guice;
import com.google.inject.Module;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.allurefw.allure1.AllureException;
import org.allurefw.report.allure1.Allure1TestsResults;
import org.allurefw.report.behaviors.BehaviorsPlugin;
import org.allurefw.report.config.ConfigModule;
import org.allurefw.report.defects.DefectsPlugin;
import org.allurefw.report.environment.EnvironmentPlugin;
import org.allurefw.report.graph.GraphPlugin;
import org.allurefw.report.issue.IssueModule;
import org.allurefw.report.jackson.JacksonMapperModule;
import org.allurefw.report.junit.JunitTestsResults;
import org.allurefw.report.opensancefont.OpenSansFontPlugin;
import org.allurefw.report.packages.PackagesPlugin;
import org.allurefw.report.timeline.TimelinePlugin;
import org.allurefw.report.tms.TmsModule;
import org.allurefw.report.total.TotalPlugin;
import org.allurefw.report.writer.WriterModule;
import org.allurefw.report.xunit.XunitPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.Files.copy;

/**
 * @author Dmitry Baev baev@qameta.io
 *         Date: 30.01.16
 */
public class ReportGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportGenerator.class);

    private final Path[] inputs;

    public ReportGenerator(Path... inputs) {
        this.inputs = inputs;
    }

    public void generate(Path output) {
        createDirectory(output, "Could not create output directory");

        List<TestsResults> sources = Stream.of(inputs)
                .flatMap(input -> Stream.of(new Allure1TestsResults(input), new JunitTestsResults(input)))
                .collect(Collectors.toList());

        List<AbstractPlugin> plugins = getPlugins();
        Set<String> pluginsWithStaticContent = plugins.stream()
                .filter(AbstractPlugin::hasStaticContent)
                .map(AbstractPlugin::getPluginName)
                .collect(Collectors.toSet());
        writeIndexHtml(output, pluginsWithStaticContent);

        Path pluginsDir = output.resolve("plugins");
        pluginsWithStaticContent.forEach(pluginName -> {
            Path pluginDir = pluginsDir.resolve(pluginName);
            unpackReportPlugin(pluginDir, pluginName);
        });

        List<Module> all = new ArrayList<>();
        all.addAll(getModules());
        all.addAll(plugins);
        Guice.createInjector(new ProcessStageModule(sources, all))
                .getInstance(ProcessStage.class)
                .run(output);
    }

    private List<AbstractPlugin> getPlugins() {
        return Arrays.asList(
                new OpenSansFontPlugin(),
                new DefectsPlugin(),
                new XunitPlugin(),
                new BehaviorsPlugin(),
                new PackagesPlugin(),
                new TimelinePlugin(),
                new GraphPlugin(),
                new PackagesPlugin(),
                new IssueModule(),
                new TmsModule()
        );
    }

    private List<Module> getModules() {
        return Arrays.asList(
                new ConfigModule(inputs),
                new JacksonMapperModule(),
                new WriterModule(),
                new EnvironmentPlugin(),
                new TotalPlugin()
        );
    }

    private void unpackReportPlugin(Path outputDirectory, String pluginName) {
        try {
            Pattern pattern = Pattern.compile("^allure" + pluginName + "/(.+)");
            ClassLoader loader = getClass().getClassLoader();
            for (ClassPath.ResourceInfo info : ClassPath.from(loader).getResources()) {
                Matcher matcher = pattern.matcher(info.getResourceName());
                if (matcher.find()) {
                    String resourcePath = matcher.group(1);
                    Path dest = outputDirectory.resolve(resourcePath);
                    try (InputStream input = info.url().openStream()) {
                        Files.createDirectories(dest.getParent());
                        copy(input, dest);
                        LOGGER.debug("{} successfully copied.", resourcePath);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error during plugin unpack: {}", e);
        }
    }

    public void writeIndexHtml(Path outputDirectory, Set<String> facePluginNames) {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_23);
        cfg.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "tpl");
        Path indexHtml = outputDirectory.resolve("index.html");
        try (BufferedWriter writer = Files.newBufferedWriter(indexHtml, StandardOpenOption.CREATE)) {
            Template template = cfg.getTemplate("index.html.ftl");
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("plugins", facePluginNames);
            template.process(dataModel, writer);
        } catch (IOException | TemplateException e) {
            LOGGER.error("Could't process index file", e);
        }
    }

    public void createDirectory(Path directory, String message) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new AllureException(message, e);
        }
    }
}
