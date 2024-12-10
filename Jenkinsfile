pipeline {
    agent any

    parameters {
        string(name: 'APP_NAME', defaultValue: 'minecraft-agent', description: 'Application name used for Docker image')
        choice(name: 'DEPLOY_ENV', choices: ['hetzner', 'dev', 'staging', 'prod'], description: 'Deployment environment')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Spring Boot App') {
            steps {
                bat 'mvnw.cmd clean package -DskipTests'
            }
        }

        stage('Deploy to Server') {
            steps {
                script {
                    def deployConfig = load "deploy/dev/config.groovy"

                    // Create temporary deployment files
                    def tempDir = pwd(tmp: true)
                    // Create a temporary directory for deployment files
                    withCredentials([sshUserPrivateKey(credentialsId: 'hetzner-ssh-key',
                                                                         keyFileVariable: 'SSH_KEY',
                                                                         usernameVariable: 'SSH_USER')]) {
                        powershell """
                            # Get current user
                            \$currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

                            # Fix permissions
                            icacls "${SSH_KEY}" /inheritance:r
                            icacls "${SSH_KEY}" /grant:r "\${currentUser}:(F)"

                            # Create temp directory for deployment files
                            ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no ${SSH_USER}@${deployConfig.DOMAIN_NAME} 'mkdir -p /tmp/deploy-${BUILD_NUMBER}'

                            # Copy files using scp (Windows compatible)
                            scp -i ${SSH_KEY} -r -o StrictHostKeyChecking=no "target/*.jar" ${SSH_USER}@${deployConfig.DOMAIN_NAME}:/tmp/deploy-${BUILD_NUMBER}/app.jar
                            scp -i ${SSH_KEY} -r -o StrictHostKeyChecking=no "deploy/nginx" ${SSH_USER}@${deployConfig.DOMAIN_NAME}:/tmp/deploy-${BUILD_NUMBER}/
                            scp -i ${SSH_KEY} -r -o StrictHostKeyChecking=no "deploy/service" ${SSH_USER}@${deployConfig.DOMAIN_NAME}:/tmp/deploy-${BUILD_NUMBER}/

                            ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no ${SSH_USER}@${deployConfig.DOMAIN_NAME} @'
sudo mkdir -p /opt/minecraft-agent
sudo mv /tmp/app.jar /opt/minecraft-agent
sudo cp /tmp/deploy-${BUILD_NUMBER}/nginx/minecraft-agent.conf /etc/nginx/conf.d/
sudo cp /tmp/deploy-${BUILD_NUMBER}/serivce/minecraft-agent.serivce /etc/systemd/system
cd /opt/minecraft-agent
sudo systemctl daemon-reload
sudo systemctl enable minecraft-agent
sudo systemctl restart minecraft-agent
sudo systemctl restart nginx
rm -rf /tmp/deploy-${BUILD_NUMBER}
timeout 60 bash -c \\\"while ! curl -s http://localhost:8080/actuator/health | grep UP; do sleep 5; done\\\"
'@
                        """
                    }
                }
            }
        }
    }

        post {
            always {
                cleanWs()
            }
        }
}