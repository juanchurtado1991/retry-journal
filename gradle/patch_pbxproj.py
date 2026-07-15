import re

file_path = "/Users/juan/ghost-sync-kmp/retry-sample/iosApp/iosApp.xcodeproj/project.pbxproj"
with open(file_path, "r") as f:
    content = f.read()

# 1. Clean up any existing FRAMEWORK_SEARCH_PATHS and OTHER_LDFLAGS from buildSettings
content = re.sub(r'\s*FRAMEWORK_SEARCH_PATHS = \([\s\S]*?\);', '', content)
content = re.sub(r'\s*OTHER_LDFLAGS = \([\s\S]*?\);', '', content)

# 2. Fix ENABLE_USER_SCRIPT_SANDBOXING
content = content.replace("ENABLE_USER_SCRIPT_SANDBOXING = YES;", "ENABLE_USER_SCRIPT_SANDBOXING = NO;")

# 3. Add clean OTHER_LDFLAGS and FRAMEWORK_SEARCH_PATHS to target configurations
target_configs = ["0D28CA6E30075F2D00FBEE69", "0D28CA6F30075F2D00FBEE69"]
for config_id in target_configs:
    pattern = r"(" + config_id + r" /\* .* \*/ = \{\n\s*isa = XCBuildConfiguration;\n\s*buildSettings = \{)"
    replacement = r"""\1
				FRAMEWORK_SEARCH_PATHS = (
					"$(inherited)",
					"$(SRCROOT)/../../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)",
				);
				OTHER_LDFLAGS = (
					"$(inherited)",
					"-framework",
					"ComposeApp",
				);"""
    content = re.sub(pattern, replacement, content)

# 4. Clean up and re-insert Run Script build phase
build_phase_id = "0D28CA7F30075F2D00FBEE69 /* Run Script */"
if build_phase_id not in content:
    content = content.replace(
        "buildPhases = (\n",
        f"buildPhases = (\n\t\t\t\t{build_phase_id},\n"
    )

run_script_section = """
/* Begin PBXShellScriptBuildPhase section */
		0D28CA7F30075F2D00FBEE69 /* Run Script */ = {
			isa = PBXShellScriptBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			inputFileListPaths = (
			);
			inputPaths = (
			);
			name = "Compile Kotlin Framework";
			outputFileListPaths = (
			);
			outputPaths = (
			);
			runOnlyForDeploymentPostprocessing = 0;
			shellPath = /bin/sh;
			shellScript = "cd /Users/juan/ghost-sync-kmp\\n./gradlew :retry-sample:composeApp:embedAndSignAppleFrameworkForXcode\\n";
		};
/* End PBXShellScriptBuildPhase section */
"""
content = re.sub(r'/\* Begin PBXShellScriptBuildPhase section \*/[\s\S]*?/\* End PBXShellScriptBuildPhase section \*/', '', content)
content = content.replace("/* Begin PBXSourcesBuildPhase section */", run_script_section + "\n/* Begin PBXSourcesBuildPhase section */")

with open(file_path, "w") as f:
    f.write(content)

print("Successfully cleaned and patched project.pbxproj")
