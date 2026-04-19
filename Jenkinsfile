pipeline {
    agent any

    environment {
        // Change variables below to match your environment
        DOCKER_CREDENTIALS_ID = 'docker-hub-credentials'
        DOCKER_REGISTRY = 'notfoundteam'
        IMAGE_NAME = "bookstore-${env.JOB_NAME}"
        TAG = "${env.BUILD_ID}"
    }

    tools {
        // Must match the name configured in Jenkins Global Tool Configuration
        maven 'Maven 3.9'
        jdk 'JDK 21'
    }

    stages {
        stage('Checkout') {
            steps {
                echo 'Checking out source code...'
                checkout scm
            }
        }

        stage('Build & Test') {
            steps {
                echo 'Compiling and Running Unit Tests...'
                // Skip tests for faster builds during dev phase if needed: -DskipTests
                sh 'mvn clean package'
            }
            post {
                always {
                    junit 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                echo 'Skipping SonarQube in basic template. Add sonar-maven-plugin here.'
                // sh 'mvn sonar:sonar -Dsonar.projectKey=${IMAGE_NAME} -Dsonar.host.url=http://your-sonarqube-server'
            }
        }

        stage('Build Docker Image') {
            steps {
                echo 'Building Docker image...'
                script {
                    dockerImage = docker.build("${DOCKER_REGISTRY}/${IMAGE_NAME}:${TAG}", ".")
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                echo 'Pushing image to Docker Hub...'
                script {
                    docker.withRegistry('https://index.docker.io/v1/', "${DOCKER_CREDENTIALS_ID}") {
                        dockerImage.push()
                        // Optional: Tag as latest
                        dockerImage.push('latest')
                    }
                }
            }
        }

        stage('Deploy/Update K8s or Server') {
            steps {
                echo 'Deploying to staging environment...'
                // sh 'kubectl set image deployment/${IMAGE_NAME} ${IMAGE_NAME}=${DOCKER_REGISTRY}/${IMAGE_NAME}:${TAG}'
            }
        }
    }

    post {
        success {
            echo "Success: Build and Deploy finished."
            // mail to: 'team@notfound.com', subject: "SUCCESS: ${env.JOB_NAME}", body: "Build has succeeded."
        }
        failure {
            echo "Failed: Pipeline failed!"
            // mail to: 'team@notfound.com', subject: "FAILED: ${env.JOB_NAME}", body: "Build has failed."
        }
    }
}
