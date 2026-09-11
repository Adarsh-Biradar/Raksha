pipeline {
  agent {
    kubernetes {
      label 'kaniko'
    }
  }
  environment {
    IMAGE_TAG = "${env.BUILD_NUMBER}"
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scm
      }
    }
    // Runs in its own self-contained pod (maven image, defined inline) rather than the kaniko
    // agent, which has no JDK/Maven. Gates everything after it: a failing test fails the build
    // before any image is built or pushed.
    stage('Test') {
      agent {
        kubernetes {
          yaml '''
            apiVersion: v1
            kind: Pod
            spec:
              containers:
              - name: maven
                image: maven:3.9.9-eclipse-temurin-21
                command: ["sleep"]
                args: ["infinity"]
          '''
        }
      }
      steps {
        checkout scm
        container('maven') {
          dir('backend') {
            // mvn itself fails the build (and this stage) on any test failure - that alone is the
            // gate. No `junit` post-processing step: the junit plugin isn't installed on this
            // Jenkins instance, and using it here previously crashed the whole pipeline immediately
            // after a successful test run (NoSuchMethodError, build #17).
            sh 'mvn -B test'
          }
        }
      }
    }
    stage('Build & Push API image') {
      steps {
        container('kaniko-api') {
          sh '''
            /kaniko/executor \
              --context=dir://${WORKSPACE}/backend \
              --dockerfile=${WORKSPACE}/backend/Dockerfile \
              --destination=docker.io/adarshbiradar/raksha-api:${IMAGE_TAG} \
              --destination=docker.io/adarshbiradar/raksha-api:latest
          '''
        }
      }
    }
    stage('Build & Push Web image') {
      steps {
        container('kaniko-web') {
          sh '''
            /kaniko/executor \
              --context=dir://${WORKSPACE}/frontend \
              --dockerfile=${WORKSPACE}/frontend/Dockerfile \
              --destination=docker.io/adarshbiradar/raksha-web:${IMAGE_TAG} \
              --destination=docker.io/adarshbiradar/raksha-web:latest
          '''
        }
      }
    }
    stage('Deploy to k3s') {
      steps {
        container('kubectl') {
          sh '''
            kubectl -n raksha set image deployment/raksha-api api=docker.io/adarshbiradar/raksha-api:${IMAGE_TAG}
            kubectl -n raksha set image deployment/raksha-web web=docker.io/adarshbiradar/raksha-web:${IMAGE_TAG}
            kubectl -n raksha rollout status deployment/raksha-api
            kubectl -n raksha rollout status deployment/raksha-web
          '''
        }
      }
    }
  }
}
