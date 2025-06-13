#!/bin/bash

# Usage: ./bump_version.sh alpha|beta|release

FILE="app/build.gradle"
TYPE=$1

if [[ -z "$TYPE" ]]; then
  echo "Usage: $0 alpha|beta|release"
  exit 1
fi

# Extract current versionCode and versionName
current_code=$(grep versionCode $FILE | awk '{print $2}')
current_name=$(grep versionName $FILE | awk -F'"' '{print $2}')

# Increment versionCode
new_code=$((current_code + 1))

# Parse current versionName
IFS='.-' read -r major minor patch pre pre_num <<< "$current_name"

if [[ "$TYPE" == "alpha" ]]; then
  if [[ "$pre" == "alpha" ]]; then
    pre_num=$((pre_num + 1))
  else
    pre="alpha"
    pre_num=1
  fi
  new_name="$major.$minor.$patch-alpha.$pre_num"
elif [[ "$TYPE" == "beta" ]]; then
  if [[ "$pre" == "beta" ]]; then
    pre_num=$((pre_num + 1))
  else
    pre="beta"
    pre_num=1
  fi
  new_name="$major.$minor.$patch-beta.$pre_num"
else
  new_name="$major.$minor.$patch"
fi

# Update build.gradle
sed -i '' "s/versionCode $current_code/versionCode $new_code/" $FILE
sed -i '' "s/versionName \"$current_name\"/versionName \"$new_name\"/" $FILE

echo "Updated to versionCode $new_code, versionName $new_name"

# Git operations
echo "Committing version changes..."
git add $FILE
git commit -m "Bump version to $new_name (code: $new_code)"

# Get the last tag
last_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "initial")

# Generate release notes
echo "Generating release notes..."
release_notes="## Changes since $last_tag\n\n"
if [[ "$last_tag" == "initial" ]]; then
    # If this is the first tag, get all commits
    commits=$(git log --pretty=format:"- %s (%h)" --reverse)
else
    # Get commits between last tag and HEAD
    commits=$(git log --pretty=format:"- %s (%h)" --reverse "$last_tag..HEAD")
fi

# Filter out version bump commits and add to release notes
release_notes+=$(echo "$commits" | grep -v "Bump version to")

echo "Creating tag v$new_name..."
git tag -a "v$new_name" -m "Release version $new_name

$release_notes"

echo "Pushing changes and tag..."
git push
git push origin "v$new_name"

echo "Version bump complete!"
echo -e "\nRelease notes for v$new_name:"
echo -e "$release_notes" 