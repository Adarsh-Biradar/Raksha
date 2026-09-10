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
