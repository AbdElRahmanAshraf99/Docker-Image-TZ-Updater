package org;

import com.fasterxml.jackson.databind.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.io.*;

import java.io.*;
import java.nio.file.*;

public class DockerImageTzCreator {
	private static final String DOCKERFILE_TEMPLATE =
			"FROM eclipse-temurin:8-jre-alpine\n" + "# Set timezone (Africa/Cairo with DST)\n"
					+ "RUN apk update && \\\n" + "    apk add --no-cache tzdata && \\\n"
					+ "    cp /usr/share/zoneinfo/Africa/Cairo /etc/localtime && \\\n"
					+ "    echo \"Africa/Cairo\" > /etc/timezone\n" + "WORKDIR /opt/app\n" + "COPY app.jar app.jar\n"
					+ "ENTRYPOINT [\"java\",\"-jar\",\"app.jar\"]\n" + "ENV TZ=Africa/Cairo\n";

	public static void main(String[] args) {
		if (args.length > 1) {
			System.err.println("Usage: java -jar docker-image-tz-creator.jar [working-directory]");
			System.err.println("  If no directory specified, uses current directory");
			System.err.println("  Processes all .tar files from old_tars/ and outputs to new_tars/");
			System.exit(1);
		}

		String workingDir = args.length == 1 ? args[0] : ".";
		File baseDir = new File(workingDir);

		if (!baseDir.exists() || !baseDir.isDirectory()) {
			System.err.println("Error: Working directory does not exist: " + workingDir);
			System.exit(1);
		}

		try {
			DockerImageTzCreator creator = new DockerImageTzCreator();
			creator.processAllDockerImages(baseDir);
		}
		catch (Exception e) {
			System.err.println("Error processing Docker images: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}

	public void processAllDockerImages(File baseDir) throws Exception {
		File oldTarsDir = new File(baseDir, "old_tars");
		File newTarsDir = new File(baseDir, "new_tars");

		// Check if old_tars directory exists
		if (!oldTarsDir.exists() || !oldTarsDir.isDirectory()) {
			System.err.println("Error: old_tars directory does not exist in: " + baseDir.getAbsolutePath());
			System.err.println("Please create the old_tars directory and place your Docker tar files there.");
			System.exit(1);
		}

		// Create new_tars directory if it doesn't exist
		if (!newTarsDir.exists()) {
			if (!newTarsDir.mkdirs()) {
				throw new IOException("Failed to create new_tars directory: " + newTarsDir.getAbsolutePath());
			}
			System.out.println("Created new_tars directory: " + newTarsDir.getAbsolutePath());
		}

		// Find all .tar files in old_tars directory
		File[] tarFiles = oldTarsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".tar"));

		if (tarFiles == null || tarFiles.length == 0) {
			System.out.println("No .tar files found in old_tars directory: " + oldTarsDir.getAbsolutePath());
			return;
		}

		System.out.println("Found " + tarFiles.length + " tar file(s) to process:");
		for (File tarFile : tarFiles) {
			System.out.println("  - " + tarFile.getName());
		}
		System.out.println();

		int successCount = 0;
		int failCount = 0;

		// Process each tar file
		for (File tarFile : tarFiles) {
			try {
				System.out.println("=== Processing: " + tarFile.getName() + " ===");
				processDockerImage(tarFile, newTarsDir);
				successCount++;
				System.out.println("✓ Successfully processed: " + tarFile.getName());
				System.out.println();
			}
			catch (Exception e) {
				failCount++;
				System.err.println("✗ Failed to process: " + tarFile.getName());
				System.err.println("  Error: " + e.getMessage());
				System.out.println();
			}
		}

		System.out.println("=== Processing Summary ===");
		System.out.println("Total files: " + tarFiles.length);
		System.out.println("Successful: " + successCount);
		System.out.println("Failed: " + failCount);

		if (failCount > 0) {
			System.out.println("Some files failed to process. Check the error messages above.");
		}
	}

	public void processDockerImage(File tarFile, File outputDir) throws Exception {
		System.out.println("Processing Docker image: " + tarFile.getName());

		// Create temporary working directory
		Path tempDir = Files.createTempDirectory("docker-tz-creator");
		System.out.println("Working directory: " + tempDir);

		try {
			// Extract tar file
			extractTarFile(tarFile, tempDir);

			// Find and extract JAR file
			File jarFile = findAndExtractJarFile(tempDir);

			// Get original image name and tag
			String[] imageInfo = getImageNameAndTag(tempDir);
			String imageName = imageInfo[0];
			if (imageName == null)
				imageName = tarFile.getName().substring(0, tarFile.getName().indexOf(".tar"));
			String imageTag = imageInfo[1];
			if (imageTag == null)
				imageTag = "latest";

			// Create new build directory
			Path buildDir = Files.createTempDirectory("docker-build");

			// Copy JAR file to build directory
			File targetJar = new File(buildDir.toFile(), "app.jar");
			FileUtils.copyFile(jarFile, targetJar);

			// Create Dockerfile
			File dockerfile = new File(buildDir.toFile(), "Dockerfile");
			FileUtils.writeStringToFile(dockerfile, DOCKERFILE_TEMPLATE, "UTF-8");

			// Build new Docker image
			String newImageName = imageName + "-tz";
			buildDockerImage(buildDir, newImageName, imageTag);

			// Export new Docker image
			String outputTarName = imageName + "-tz-" + imageTag + ".tar";
			File outputTarFile = new File(outputDir, outputTarName);
			exportDockerImage(newImageName, imageTag, outputTarFile.getAbsolutePath());

			System.out.println("Successfully created: " + outputTarFile.getAbsolutePath());

			FileUtils.deleteDirectory(buildDir.toFile());

		}
		finally {
			FileUtils.deleteDirectory(tempDir.toFile());
		}
	}

	private void extractTarFile(File tarFile, Path outputDir) throws Exception {
		System.out.println("Extracting tar file...");

		try (FileInputStream fis = new FileInputStream(tarFile);
				TarArchiveInputStream tais = new TarArchiveInputStream(fis)) {

			TarArchiveEntry entry;
			while ((entry = tais.getNextTarEntry()) != null) {
				File outputFile = new File(outputDir.toFile(), entry.getName());

				if (entry.isDirectory()) {
					outputFile.mkdirs();
					continue;
				}

				outputFile.getParentFile().mkdirs();

				try (FileOutputStream fos = new FileOutputStream(outputFile)) {
					IOUtils.copy(tais, fos);
				}
			}
		}
		System.out.println("Tar extraction completed");
	}

	private File findAndExtractJarFile(Path extractedDir) throws Exception {
		System.out.println("Looking for JAR file in Docker layers...");

		// First, look for directories that might contain layer.tar
		File[] directories = extractedDir.toFile().listFiles(File::isDirectory);
		if (directories != null) {
			for (File dir : directories) {
				File layerTar = new File(dir, "layer.tar");
				if (layerTar.exists()) {
					File jarFile = extractJarFromLayer(layerTar);
					if (jarFile != null) {
						return jarFile;
					}
				}
			}
		}

		// If not found in subdirectories, look for .tar files directly in extracted directory
		File[] layerFiles = extractedDir.toFile()
										.listFiles((dir, name) -> name.endsWith(".tar") || name.equals("layer.tar"));

		if (layerFiles != null) {
			for (File layerFile : layerFiles) {
				File jarFile = extractJarFromLayer(layerFile);
				if (jarFile != null) {
					return jarFile;
				}
			}
		}

		throw new RuntimeException("JAR file not found in any Docker layer");
	}

	private File extractJarFromLayer(File layerTar) throws Exception {
		Path tempLayerDir = Files.createTempDirectory("layer-extract");

		try (FileInputStream fis = new FileInputStream(layerTar);
				TarArchiveInputStream tais = new TarArchiveInputStream(fis)) {

			TarArchiveEntry entry;
			while ((entry = tais.getNextTarEntry()) != null) {
				if (entry.getName().equals("opt/app/app.jar") || entry.getName().endsWith(".jar")) {

					File jarFile = new File(tempLayerDir.toFile(), "app.jar");
					try (FileOutputStream fos = new FileOutputStream(jarFile)) {
						IOUtils.copy(tais, fos);
					}
					System.out.println("Found JAR file: " + entry.getName());
					return jarFile;
				}
			}
		}
		catch (Exception e) {
			FileUtils.deleteDirectory(tempLayerDir.toFile());
			throw e;
		}

		FileUtils.deleteDirectory(tempLayerDir.toFile());
		return null;
	}

	private String[] getImageNameAndTag(Path extractedDir) throws Exception {
		// Try to read manifest.json to get repository info
		File manifestFile = new File(extractedDir.toFile(), "manifest.json");
		if (manifestFile.exists()) {
			ObjectMapper mapper = new ObjectMapper();
			JsonNode manifest = mapper.readTree(manifestFile);

			if (manifest.isArray() && !manifest.isEmpty()) {
				JsonNode firstImage = manifest.get(0);
				JsonNode repoTags = firstImage.get("RepoTags");

				if (repoTags != null && repoTags.isArray() && !repoTags.isEmpty()) {
					String repoTag = repoTags.get(0).asText();
					String[] parts = repoTag.split(":");
					return new String[] { parts[0], parts.length > 1 ? parts[1] : "latest" };
				}
			}
		}

		return new String[] { null, null };
	}

	private void buildDockerImage(Path buildDir, String imageName, String imageTag) throws Exception {
		System.out.println("Building Docker image: " + imageName + ":" + imageTag);

		ProcessBuilder pb = new ProcessBuilder("docker", "build", "--tag=" + imageName + ":" + imageTag, ".");
		pb.directory(buildDir.toFile());
		pb.inheritIO();

		Process process = pb.start();
		int exitCode = process.waitFor();

		if (exitCode != 0) {
			throw new RuntimeException("Docker build failed with exit code: " + exitCode);
		}

		System.out.println("Docker build completed successfully");
	}

	private void exportDockerImage(String imageName, String imageTag, String outputFileName) throws Exception {
		System.out.println("Exporting Docker image to: " + outputFileName);

		ProcessBuilder pb = new ProcessBuilder("docker", "save", "-o", outputFileName, imageName + ":" + imageTag);
		pb.inheritIO();

		Process process = pb.start();
		int exitCode = process.waitFor();

		if (exitCode != 0) {
			throw new RuntimeException("Docker save failed with exit code: " + exitCode);
		}

		System.out.println("Docker export completed successfully");
	}
}
