#!/usr/bin/env node
// Prints the artifact-to-commit manifest of a release: the registry digest of every service image built from
// RELEASE_TAG (the commit SHA). Jenkins archives it with the build (docs/ci.md).
import { execFileSync } from "node:child_process";

const services = ["auth-service", "user-service", "travel-service", "payment-service", "engagement-service",
  "search-service", "api-gateway", "admin-ui"];
const { IMAGE_PREFIX: prefix, RELEASE_TAG: commit, BUILD_NUMBER: build } = process.env;
if (!prefix || !commit) throw new Error("IMAGE_PREFIX and RELEASE_TAG are required");

const images = {};
for (const service of services) {
  const image = `${prefix}/${service}:${commit}`;
  images[service] = execFileSync("docker", ["image", "inspect", "--format", "{{ index .RepoDigests 0 }}", image])
    .toString().trim();
}
console.log(JSON.stringify({ commit, build: build ?? null, createdAt: new Date().toISOString(), images }, null, 2));
