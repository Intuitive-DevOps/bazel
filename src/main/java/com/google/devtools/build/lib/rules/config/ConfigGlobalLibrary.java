// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License

package com.google.devtools.build.lib.rules.config;

import static com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.COMMAND_LINE_OPTION_PREFIX;

import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.Settings;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigGlobalLibraryApi;
import com.google.devtools.build.lib.starlarkbuildapi.config.ConfigurationTransitionApi;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * Implementation of {@link ConfigGlobalLibraryApi}.
 *
 * <p>A collection of top-level Starlark functions pertaining to configuration.
 */
public class ConfigGlobalLibrary implements ConfigGlobalLibraryApi {

  @Override
  public ConfigurationTransitionApi transition(
      StarlarkCallable implementation,
      Sequence<?> inputs, // <String> expected
      Sequence<?> outputs, // <String> expected
      StarlarkThread thread)
      throws EvalException {
    StarlarkSemantics semantics = thread.getSemantics();
    List<String> inputsList = Sequence.cast(inputs, String.class, "inputs");
    List<String> outputsList = Sequence.cast(outputs, String.class, "outputs");
    validateBuildSettingKeys(inputsList, Settings.INPUTS);
    validateBuildSettingKeys(outputsList, Settings.OUTPUTS);
    BazelModuleContext moduleContext =
        BazelModuleContext.of(Module.ofInnermostEnclosingStarlarkFunction(thread));
    Location location = thread.getCallerLocation();
    return StarlarkDefinedConfigTransition.newRegularTransition(
        implementation,
        inputsList,
        outputsList,
        semantics,
        moduleContext.label(),
        location,
        moduleContext.repoMapping());
  }

  // TODO(b/237422931): move into testing module
  @Override
  public ConfigurationTransitionApi analysisTestTransition(
      Dict<?, ?> changedSettings, // <String, String> expected
      StarlarkThread thread)
      throws EvalException {
    Map<String, Object> changedSettingsMap =
        Dict.cast(changedSettings, String.class, Object.class, "changed_settings dict");
    validateBuildSettingKeys(changedSettingsMap.keySet(), Settings.OUTPUTS);
    BazelModuleContext moduleContext =
        BazelModuleContext.of(Module.ofInnermostEnclosingStarlarkFunction(thread));
    Location location = thread.getCallerLocation();
    return StarlarkDefinedConfigTransition.newAnalysisTestTransition(
        changedSettingsMap, moduleContext.repoMapping(), moduleContext.label(), location);
  }

  private void validateBuildSettingKeys(Iterable<String> optionKeys, Settings keyErrorDescriptor)
      throws EvalException {

    HashSet<String> processedOptions = Sets.newHashSet();
    String singularErrorDescriptor = keyErrorDescriptor == Settings.INPUTS ? "input" : "output";

    for (String optionKey : optionKeys) {
      if (!optionKey.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
        try {
          Label.parseAbsoluteUnchecked(optionKey);
        } catch (IllegalArgumentException e) {
          throw Starlark.errorf(
              "invalid transition %s '%s'. If this is intended as a native option, "
                  + "it must begin with //command_line_option: %s",
              singularErrorDescriptor, optionKey, e.getMessage());
        }
      } else {
        String optionName = optionKey.substring(COMMAND_LINE_OPTION_PREFIX.length());
        if (!validOptionName(optionName)) {
          throw Starlark.errorf(
              "Invalid transition %s '%s'. Cannot transition on --experimental_* or "
                  + "--incompatible_* options",
              singularErrorDescriptor, optionKey);
        }
      }
      if (!processedOptions.add(optionKey)) {
        throw Starlark.errorf("duplicate transition %s '%s'", singularErrorDescriptor, optionKey);
      }
    }
  }

  private static boolean validOptionName(String optionName) {
    if (optionName.startsWith("experimental_")) {
      // Don't allow experimental flags.
      return false;
    }

    if (optionName.equals("incompatible_enable_cc_toolchain_resolution")
        || optionName.equals("incompatible_enable_cgo_toolchain_resolution")
        || optionName.equals("incompatible_enable_apple_toolchain_resolution")
        || optionName.equals("incompatible_enable_android_toolchain_resolution")) {
      // This is specifically allowed.
      return true;
    } else if (optionName.startsWith("incompatible_")) {
      // Don't allow other incompatible flags.
      return false;
    }

    return true;
  }
}
