pipeline {
  agent { label 'lets-travel-ci' }
  options { timestamps(); disableConcurrentBuilds() }

  stages {
    stage('Toolchain') {
      steps {
        sh 'java -version && node --version && npm --version && sonar-scanner --version'
        sh 'gitleaks version && osv-scanner --version && trivy --version'
      }
    }
    stage('Backend verification') {
      steps { sh './mvnw -B -ntp clean verify' }
    }
    stage('API contracts') {
      steps { sh 'npm ci && npm run lint:api' }
    }
    stage('Dashboard tests, lint and build') {
      steps {
        dir('admin-ui') {
          sh 'npm ci'
          sh 'npm run test:ci'
          sh 'npm run lint'
          sh 'npm run build'
        }
      }
    }
    stage('Dependency advisories') {
      steps {
        sh 'npm audit --audit-level=high'
        dir('admin-ui') {
          sh 'npm audit --audit-level=high'
        }
      }
    }
    // Blocking: any committed secret or any known vulnerability in a Java or npm dependency fails the build.
    // Reviewed false positives go in .gitleaksignore; accepted vulnerabilities in osv-scanner.toml (docs/ci.md).
    stage('Security scans') {
      steps {
        sh '''
          set -eu
          mkdir -p reports/security/sbom
          gitleaks git . --redact --no-banner --report-format json --report-path reports/security/gitleaks.json
          ./mvnw -B -ntp -q org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
            -DoutputFormat=json -DoutputName=bom -DincludeTestScope=false
          cp target/bom.json reports/security/sbom/backend.cdx.json
          osv-scanner scan source --no-ignore --config osv-scanner.toml --format table --output reports/security/osv.txt \
            -L package-lock.json -L admin-ui/package-lock.json reports/security/sbom \
            || { cat reports/security/osv.txt; exit 1; }
        '''
      }
    }
    stage('Deployment playbook checks') {
      steps {
        sh 'ansible-playbook --syntax-check -i infra/ansible/inventory.ini infra/ansible/site.yml'
        sh 'ansible-lint infra/ansible/site.yml infra/ansible/prepare.yml infra/ansible/deploy.yml infra/ansible/rollout.yml'
      }
    }
    stage('SonarQube analysis') {
      steps {
        withSonarQubeEnv('lets-travel-sonarqube') {
          sh 'sonar-scanner'
        }
      }
    }
    stage('Quality gate') {
      steps {
        timeout(time: 10, unit: 'MINUTES') {
          waitForQualityGate abortPipeline: true
        }
      }
    }
    stage('Publish release images') {
      when { expression { env.BRANCH_NAME == 'main' || (!env.BRANCH_NAME?.trim() && env.GIT_BRANCH in ['main', 'origin/main']) } }
      steps {
        script {
          if (!env.IMAGE_PREFIX?.trim() || !env.REGISTRY_HOST?.trim()) {
            error('IMAGE_PREFIX and REGISTRY_HOST must be configured for main builds')
          }
          env.RELEASE_TAG = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
        }
        withCredentials([usernamePassword(credentialsId: 'lets-travel-registry',
          usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASSWORD')]) {
          sh '''
            set -eu
            export DOCKER_BUILDKIT=1
            source_date_epoch=$(git show -s --format=%ct "$RELEASE_TAG")
            printf '%s' "$REGISTRY_PASSWORD" | docker login "$REGISTRY_HOST" -u "$REGISTRY_USER" --password-stdin
            trap 'docker logout "$REGISTRY_HOST" >/dev/null 2>&1 || true' EXIT
            for service in auth-service user-service travel-service payment-service engagement-service search-service api-gateway admin-ui; do
              # travel-service sources live in the lets-travel/ directory.
              context=$service
              [ "$service" = travel-service ] && context=lets-travel
              docker build --build-arg "SOURCE_DATE_EPOCH=$source_date_epoch" \
                --label "org.opencontainers.image.revision=$RELEASE_TAG" \
                -t "$IMAGE_PREFIX/$service:ci-$BUILD_NUMBER-$RELEASE_TAG" "$context"
            done
            # Image policy: no HIGH or CRITICAL vulnerability that has a fix may be published. Candidates are pushed
            # under their ci- tag and scanned from the registry (exporting from the remote daemon is far slower);
            # only images that pass get the release tag below.
            mkdir -p reports/security/images
            export TRIVY_USERNAME="$REGISTRY_USER" TRIVY_PASSWORD="$REGISTRY_PASSWORD"
            for service in auth-service user-service travel-service payment-service engagement-service search-service api-gateway admin-ui; do
              docker push -q "$IMAGE_PREFIX/$service:ci-$BUILD_NUMBER-$RELEASE_TAG"
              trivy image --quiet --image-src remote --insecure --timeout 10m \
                --cache-dir /home/jenkins/agent/trivy-cache --scanners vuln --ignore-unfixed \
                --severity HIGH,CRITICAL --exit-code 1 --format table \
                --output "reports/security/images/$service.txt" "$IMAGE_PREFIX/$service:ci-$BUILD_NUMBER-$RELEASE_TAG" \
                || { cat "reports/security/images/$service.txt"; exit 1; }
            done
            for service in auth-service user-service travel-service payment-service engagement-service search-service api-gateway admin-ui; do
              target="$IMAGE_PREFIX/$service:$RELEASE_TAG"
              candidate="$IMAGE_PREFIX/$service:ci-$BUILD_NUMBER-$RELEASE_TAG"
              if docker pull "$target" >/dev/null 2>&1; then
                published_revision=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$target")
                published_id=$(docker image inspect --format '{{ .Id }}' "$target")
                candidate_id=$(docker image inspect --format '{{ .Id }}' "$candidate")
                if [ "$published_revision" != "$RELEASE_TAG" ] || [ "$published_id" != "$candidate_id" ]; then
                  echo "Existing release tag differs from the verified build: $target" >&2
                  exit 1
                fi
                echo "Already published: $target"
                continue
              fi
              docker tag "$candidate" "$target"
              docker push "$target"
            done
            # Artifact-to-commit manifest: the exact image digests this commit was released as.
            node scripts/ci/release-manifest.mjs > release-manifest.json
            cat release-manifest.json
          '''
        }
      }
    }
    stage('Deploy reviewed main') {
      when { expression { env.BRANCH_NAME == 'main' || (!env.BRANCH_NAME?.trim() && env.GIT_BRANCH in ['main', 'origin/main']) } }
      steps {
        script {
          if (!env.DEPLOY_INVENTORY?.trim()) {
            error('DEPLOY_INVENTORY must select a reviewed target for main builds')
          }
        }
        withCredentials([
          usernamePassword(credentialsId: 'lets-travel-registry',
            usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASSWORD'),
          sshUserPrivateKey(credentialsId: 'lets-travel-deploy-ssh', keyFileVariable: 'DEPLOY_SSH_KEY'),
          file(credentialsId: 'lets-travel-release-env', variable: 'RELEASE_ENV_FILE')
        ]) {
          sh '''
            # Jenkins traces every command (sh -x); tracing would print each secret the file sets.
            set +x
            set -eu
            set -a
            . "$RELEASE_ENV_FILE"
            set +a
            ansible-playbook -i "$DEPLOY_INVENTORY" --private-key "$DEPLOY_SSH_KEY" infra/ansible/deploy.yml \
              -e "release_tag=$RELEASE_TAG" \
              -e "image_prefix=$IMAGE_PREFIX" \
              -e "registry_host=$REGISTRY_HOST"
          '''
        }
      }
    }
    // Performance smoke against the deployed release: once search answers (cold start), two users search for 15 s;
    // p99 must stay under 5 s with under 1% errors.
    stage('Post-deploy smoke') {
      when { expression { env.BRANCH_NAME == 'main' || (!env.BRANCH_NAME?.trim() && env.GIT_BRANCH in ['main', 'origin/main']) } }
      steps {
        withCredentials([file(credentialsId: 'lets-travel-release-env', variable: 'RELEASE_ENV_FILE')]) {
          sh '''
            # Jenkins traces every command (sh -x); tracing would print each secret the file sets.
            set +x
            set -eu
            set -a
            . "$RELEASE_ENV_FILE"
            set +a
            export BASE_URL="${DEPLOY_BASE_URL:-https://deploy-host:8443}" INSECURE_TLS=1 LOAD_ACCOUNTS=admin
            export ADMIN_EMAIL="$ADMIN_USERNAME"
            node scripts/load/load.mjs --scenario search --concurrency 2 --duration 15 --warmup 120 --out reports/smoke
          '''
        }
      }
    }
  }
  post {
    always {
      junit testResults: '**/target/surefire-reports/*.xml,admin-ui/reports/vitest-junit.xml', allowEmptyResults: true
      archiveArtifacts artifacts: '**/target/*.jar,admin-ui/dist/**,release-manifest.json,reports/**,**/target/site/jacoco/**,admin-ui/coverage/**',
        allowEmptyArchive: true
    }
  }
}
