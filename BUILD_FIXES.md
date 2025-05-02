# Build Fixes Documentation

## Overview
This document outlines the build issues in the TAK Lite project and provides a step-by-step plan to resolve them.

## Current Issues

### 1. ViewBinding Generation Issues
**Location**: `app/src/main/kotlin/com/tak/lite/ui/audio/AudioFragment.kt`
- **Issue**: `AudioFragmentBinding` unresolved reference
- **Root Cause**: kapt is disabled, preventing ViewBinding class generation
- **Files Affected**:
  - `app/build.gradle`
  - `app/src/main/kotlin/com/tak/lite/ui/audio/AudioFragment.kt`
- **Solution**:
  1. Remove temporary kapt disable code
  2. Verify ViewBinding is properly configured
  3. Clean and rebuild project

### 2. AudioFragment UI Issues
**Location**: `app/src/main/kotlin/com/tak/lite/ui/audio/AudioFragment.kt`
- **Issues**:
  - Unresolved reference: `channelFrequencyInput`
  - Non-exhaustive when expression in `updateConnectionState`
- **Files Affected**:
  - `app/src/main/kotlin/com/tak/lite/ui/audio/AudioFragment.kt`
  - `app/src/main/res/layout/dialog_add_channel.xml`
- **Solution**:
  1. Verify layout file contains `channelFrequencyInput` view
  2. Add else branch to when expression
  3. Update UI binding references

### 3. AnnotationOverlayView Type Issues
**Location**: `app/src/main/kotlin/com/tak/lite/ui/map/AnnotationOverlayView.kt`
- **Issues**:
  - Unresolved references: `coordinates` and `position`
  - Type inference issues
- **Files Affected**:
  - `app/src/main/kotlin/com/tak/lite/ui/map/AnnotationOverlayView.kt`
  - `app/src/main/kotlin/com/tak/lite/model/MapAnnotation.kt`
- **Solution**:
  1. Review and fix MapAnnotation sealed class hierarchy
  2. Add proper type declarations
  3. Fix coordinate and position property access

### 4. Build Configuration Issues
**Location**: Various build configuration files
- **Issues**:
  - kapt configuration
  - ViewBinding setup
  - Dependency versions
- **Files Affected**:
  - `app/build.gradle`
  - `build.gradle`
  - `gradle.properties`
- **Solution**:
  1. Update kapt configuration
  2. Verify ViewBinding setup
  3. Review and update dependency versions

## Implementation Plan

### Phase 1: Build Configuration
1. Fix kapt configuration
2. Update ViewBinding setup
3. Review and update dependencies

### Phase 2: AudioFragment Fixes
1. Fix ViewBinding generation
2. Update UI references
3. Fix when expression

### Phase 3: AnnotationOverlayView Fixes
1. Fix MapAnnotation sealed class
2. Update coordinate and position access
3. Fix type inference issues

### Phase 4: Testing and Verification
1. Clean and rebuild project
2. Run unit tests
3. Verify UI functionality

## Notes
- Each fix should be implemented and tested independently
- Keep track of changes in version control
- Document any additional issues discovered during the fix process 