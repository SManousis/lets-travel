pipeline {
  agent { label 'travel-plan-ci' }
  options { timestamps(); disableConcurrentBuilds() }

  stages {
    stage('Toolchain') {
      steps {
        sh 'java -version && node --version && npm --version && sonar-scanner --version'
      }
    }
    stage('Backend verification') {
      steps { sh './mvnw -B -ntp verify' }
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
    stage('Deployment playbook checks') {
      steps {
        sh 'ansible-playbook --syntax-check -i infra/ansible/inventory.ini infra/ansible/site.yml'
        sh 'ansible-lint infra/ansible/site.yml infra/ansible/prepare.yml infra/ansible/deploy.yml'
      }
    }
    stage('SonarQube analysis') {
      steps {
        withSonarQubeEnv('travel-plan-sonarqube') {
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
        withCredentials([usernamePassword(credentialsId: 'travel-plan-registry',
          usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASSWORD')]) {
          sh '''
            set -eu
            export DOCKER_BUILDKIT=1
            source_date_epoch=$(git show -s --format=%ct "$RELEASE_TAG")
            printf '%s' "$REGISTRY_PASSWORD" | docker login "$REGISTRY_HOST" -u "$REGISTRY_USER" --password-stdin
            trap 'docker logout "$REGISTRY_HOST" >/dev/null 2>&1 || true' EXIT
            for service in auth-service user-service travel-service payment-service api-gateway admin-ui; do
              docker build --build-arg "SOURCE_DATE_EPOCH=$source_date_epoch" \
                --label "org.opencontainers.image.revision=$RELEASE_TAG" \
                -t "$IMAGE_PREFIX/$service:ci-$BUILD_NUMBER-$RELEASE_TAG" "$service"
            done
            for service in auth-service user-service travel-service payment-service api-gateway admin-ui; do
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
          usernamePassword(credentialsId: 'travel-plan-registry',
            usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASSWORD'),
          sshUserPrivateKey(credentialsId: 'travel-plan-deploy-ssh', keyFileVariable: 'DEPLOY_SSH_KEY'),
          file(credentialsId: 'travel-plan-release-env', variable: 'RELEASE_ENV_FILE')
        ]) {
          sh '''
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
  }
  post {
    always {
      junit testResults: '**/target/surefire-reports/*.xml,admin-ui/reports/vitest-junit.xml', allowEmptyResults: true
      archiveArtifacts artifacts: '**/target/*.jar,admin-ui/dist/**', allowEmptyArchive: true
    }
  }
}
