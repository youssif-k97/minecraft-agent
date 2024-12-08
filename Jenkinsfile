pipeline {
    agent any

    parameters {
        string(name: 'APP_NAME', defaultValue: 'minecraft-agent', description: 'Application name used for Docker image')
        choice(name: 'DEPLOY_ENV', choices: ['hetzner', 'dev', 'staging', 'prod'], description: 'Deployment environment')
    }

    environment {
        // Get Docker credentials from Jenkins credentials store
        DOCKER_CREDENTIALS = credentials('docker-hub-credentials')
        DOCKER_REGISTRY = credentials('docker-registry-url') // e.g., 'docker.io/username' or private registry

        // Get server details from Jenkins credentials
        DEPLOY_SERVER = credentials("${params.DEPLOY_ENV}-ssh-key")

        // Construct Docker image name and tag
        GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        VERSION = sh(script: 'cat VERSION || echo "1.0.0"', returnStdout: true).trim()
        DOCKER_IMAGE = "${DOCKER_REGISTRY}/${params.APP_NAME}"
        DOCKER_TAG = "${params.DEPLOY_ENV}-${VERSION}-${GIT_COMMIT_SHORT}"
        DOCKER_LATEST = "${DOCKER_IMAGE}:${params.DEPLOY_ENV}-latest"
        DOCKER_VERSIONED = "${DOCKER_IMAGE}:${DOCKER_TAG}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Spring Boot App') {
            steps {
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    sh """
                        docker build -t ${DOCKER_VERSIONED} \
                            --build-arg APP_VERSION=${VERSION} \
                            --build-arg BUILD_ENV=${params.DEPLOY_ENV} \
                            .
                        docker tag ${DOCKER_VERSIONED} ${DOCKER_LATEST}
                    """
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                script {
                    sh """
                        echo ${DOCKER_CREDENTIALS_PSW} | docker login ${DOCKER_REGISTRY} -u ${DOCKER_CREDENTIALS_USR} --password-stdin
                        docker push ${DOCKER_VERSIONED}
                        docker push ${DOCKER_LATEST}
                    """
                }
            }
        }

        stage('Deploy to Server') {
            steps {
                script {
                    def deployConfig = load "deploy/${params.DEPLOY_ENV}/config.groovy"

                    // Create a temporary directory for deployment files
                    sshagent([DEPLOY_SERVER]) {
                        sh """
                            ssh -o StrictHostKeyChecking=no ${DEPLOY_SERVER_USR}@${DEPLOY_SERVER_PSW} '
                                sudo mkdir -p /opt/minecraft-agent

                                cat > /opt/minecraft-agent/.env << EOF
                                DOCKER_IMAGE=${DOCKER_VERSIONED}
                                APP_NAME=${params.APP_NAME}
                                SPRING_PROFILE=${params.DEPLOY_ENV}
                                EOF

                                sudo cp deploy/docker-compose.yml /opt/minecraft-agent/docker-compose.yml

                                sudo cp deploy/nginx/minecraft-agent.conf /etc/nginx/conf.d/

                                docker network create web || true

                                sudo systemctl daemon-reload
                                sudo systemctl enable minecraft-server
                                sudo systemctl restart minecraft-server
                                sudo systemctl restart nginx

                                timeout 60 bash -c "until curl -s http://localhost:8080/actuator/health | grep UP; do sleep 5; done"
                            '
                        """
                    }
                }
            }
        }
    }
}