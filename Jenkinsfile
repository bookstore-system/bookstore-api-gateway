pipeline {
    agent any

    environment {
        DOCKER_REGISTRY = 'truongdocker1'
        DOCKER_CREDENTIALS_ID = 'dockerhub-creds'
        IMAGE_NAME = 'bookstore-api-gateway'
        // Tag theo build number để mỗi build ra image riêng — chuẩn CI/CD
        TAG = "${BUILD_NUMBER}"

        K8S_DEPLOYMENT = 'api-gateway-deployment'
        K8S_CONTAINER = 'api-gateway'
    }

    tools {
        maven 'Maven 3.9'
        jdk 'JDK 21'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build & Test') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Docker Build') {
            steps {
                script {
                    dockerImage = docker.build(
                        "${DOCKER_REGISTRY}/${IMAGE_NAME}:${TAG}",
                        "."
                    )
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                script {
                    docker.withRegistry(
                        'https://index.docker.io/v1/',
                        "${DOCKER_CREDENTIALS_ID}"
                    ) {
                        dockerImage.push()
                    }
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withCredentials([
                    file(credentialsId: 'jwt-public-pem', variable: 'JWT_PUBLIC_PEM'),
                    string(credentialsId: 'redis-password', variable: 'REDIS_PASSWORD')
                ]) {
                    sh '''
                export KUBECONFIG=/var/jenkins_home/.kube/config

                # Public key only: gateway does not need private.pem.
                kubectl create secret generic api-gateway-jwt-public-key \
                  --from-file=public.pem="$JWT_PUBLIC_PEM" \
                  --dry-run=client -o yaml | kubectl apply -f -

                # Redis password for gateway rate limiting. Host/port/username/SSL are in k8s/configmap.yaml.
                kubectl create secret generic api-gateway-secret \
                  --from-literal=SPRING_DATA_REDIS_PASSWORD="$REDIS_PASSWORD" \
                  --dry-run=client -o yaml | kubectl apply -f -

                # Update image tag robustly, even if the workspace still has an older build tag.
                sed -i "s|image: .*${IMAGE_NAME}:.*|image: ${DOCKER_REGISTRY}/${IMAGE_NAME}:${TAG}|g" k8s/deployment.yaml

                # Deploy app + service + ingress.
                kubectl apply -f k8s/configmap.yaml
                kubectl apply -f k8s/deployment.yaml
                kubectl apply -f k8s/service.yaml
                kubectl apply -f k8s/ingress.yaml

                kubectl rollout status deployment/${K8S_DEPLOYMENT} --timeout=180s
                '''
                }
            }
        }
    }

    post {
        success {
            echo "Build & Deploy SUCCESS"
        }
        failure {
            echo "Build FAILED"
        }
    }
}
